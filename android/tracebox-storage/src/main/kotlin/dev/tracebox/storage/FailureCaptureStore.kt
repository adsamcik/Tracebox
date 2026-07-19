package dev.tracebox.storage

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32C
import dev.tracebox.api.DiagnosticContext
import dev.tracebox.api.Diagnostics
import dev.tracebox.api.generated.GeneratedDiagnostics
import dev.tracebox.api.generated.GeneratedEmergencyRecord
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedHandledError
import dev.tracebox.api.generated.GeneratedRecord
import dev.tracebox.api.generated.GeneratedStructuralSummary
import dev.tracebox.core.GateAcceptance
import dev.tracebox.core.GateResult
import dev.tracebox.core.JvmCapturePolicy
import dev.tracebox.core.PolicyTaggedRecord
import dev.tracebox.core.RecordPriority
import dev.tracebox.core.TraceboxUncaughtExceptionHandler
import dev.tracebox.core.WriterPolicyGate

/** Raw crash bytes may only contribute an ID-free structural summary and are never package eligible. */
enum class RawArtifactDisposition { STRUCTURAL_SUMMARY_ONLY }
data class RawArtifactJournal(
    val id: ByteArray,
    val originProcessInstanceId: ByteArray,
    val originRole: Int,
    val acceptedEpoch: Long,
    val disposition: RawArtifactDisposition = RawArtifactDisposition.STRUCTURAL_SUMMARY_ONLY,
) {
    init {
        require(id.size == 32)
        require(originProcessInstanceId.size == 32)
    }
}

/** CE handler raw-artifact store with a separate, hard byte budget. */
class RawArtifactStore(private val root: Path, private val rawQuotaBytes: Long) {
    init { require(rawQuotaBytes >= 0) }

    fun preCapture(id: ByteArray, originProcessInstanceId: ByteArray, originRole: Int, acceptedEpoch: Long): Boolean {
        val journal = RawArtifactJournal(id.copyOf(), originProcessInstanceId.copyOf(), originRole, acceptedEpoch)
        val path = journalPath(id)
        if (Files.exists(path)) return false
        Files.createDirectories(root)
        forceWrite(
            path,
            "${encode(journal.id)}|${encode(journal.originProcessInstanceId)}|${journal.originRole}|${journal.acceptedEpoch}".toByteArray(),
        )
        return true
    }

    fun commitRaw(id: ByteArray, bytes: ByteArray): Boolean {
        if (journal(id) == null || bytes.size.toLong() + usedRawBytes() > rawQuotaBytes) return false
        forceWrite(rawPath(id), bytes)
        return true
    }

    fun journal(id: ByteArray): RawArtifactJournal? {
        val path = journalPath(id)
        if (!Files.isRegularFile(path)) return null
        val parts = try { Files.readString(path).trim().split('|') } catch (_: java.io.IOException) { return null }
        if (parts.size != 4) return null
        return try {
            RawArtifactJournal(decode(parts[0]), decode(parts[1]), parts[2].toInt(), parts[3].toLong())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Tracebox-generated raw bytes without a valid lifecycle journal are destroyed, never parsed. */
    fun deleteUnverifiableOrphans() {
        if (!Files.isDirectory(root)) return
        Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".tbraw") }.forEach { raw ->
                val id = raw.fileName.toString().removeSuffix(".tbraw")
                if (journalByName(id) == null) Files.deleteIfExists(raw)
            }
        }
    }

    /** Removes expired raw bytes and their binding journals, then removes any remaining invalid orphan. */
    fun expire(nowMillis: Long, ttlMillis: Long): Int {
        require(ttlMillis >= 0)
        if (!Files.isDirectory(root)) return 0
        var deleted = 0
        Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".tbraw") }
                .filter { nowMillis - Files.getLastModifiedTime(it).toMillis() >= ttlMillis }
                .forEach { raw ->
                    val id = raw.fileName.toString().removeSuffix(".tbraw")
                    if (Files.deleteIfExists(raw)) deleted++
                    Files.deleteIfExists(root.resolve("$id.tbrawjournal"))
                }
        }
        deleteUnverifiableOrphans()
        return deleted
    }

    private fun journalByName(id: String): RawArtifactJournal? =
        try { journal(decode(id)) } catch (_: IllegalArgumentException) { null }
    private fun rawPath(id: ByteArray): Path = root.resolve("${encode(id)}.tbraw")
    private fun journalPath(id: ByteArray): Path = root.resolve("${encode(id)}.tbrawjournal")
    private fun usedRawBytes(): Long = Files.list(root).use { it.filter { path -> path.fileName.toString().endsWith(".tbraw") }.mapToLong(Files::size).sum() }
    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}

/** The only handler capture-start route: it forces a lifecycle journal before capture bytes exist. */
class CrashpadCaptureLifecycle(private val rawStore: RawArtifactStore) {
    fun capture(
        rawArtifactId: ByteArray,
        originProcessInstanceId: ByteArray,
        originRole: Int,
        acceptedPolicyEpoch: Long,
        writeCaptureBytes: () -> ByteArray,
    ): Boolean {
        if (!rawStore.preCapture(rawArtifactId, originProcessInstanceId, originRole, acceptedPolicyEpoch)) return false
        return rawStore.commitRaw(rawArtifactId, writeCaptureBytes())
    }
}

/** R2.8 participant for CE raw artifacts; deletion is only complete after this participant is empty. */
class RawArtifactDeletionParticipant(private val rawStore: RawArtifactStore, private val root: Path) : DeletionParticipant {
    override fun markIneligible() = Unit

    override fun deleteOwned() {
        if (!Files.isDirectory(root)) return
        Files.list(root).use { files ->
            files.filter {
                val name = it.fileName.toString()
                name.endsWith(".tbraw") || name.endsWith(".tbrawjournal")
            }.forEach { Files.deleteIfExists(it) }
        }
    }

    override fun remainingOwned(): List<Path> {
        rawStore.deleteUnverifiableOrphans()
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { files ->
            files.filter {
                val name = it.fileName.toString()
                name.endsWith(".tbraw") || name.endsWith(".tbrawjournal")
            }.toList()
        }
    }
}

/** Durable states make spool replay recoverable after every source-retirement boundary. */
private enum class SpoolState { JOURNALED, APPENDED, ACKNOWLEDGED, RETIRED }

/**
 * Handler structural-summary spool. Its canonical content excludes IDs; `stage` writes the tuple
 * and deterministic ID before appending, and `replay` retains source until a durable acknowledgement.
 */
class StructuralSummarySpool(private val root: Path) {
    fun stage(rawId: ByteArray, extractorVersion: Int, schema: ByteArray, canonicalBody: ByteArray): String {
        require(rawId.size == 32 && schema.size == 32)
        val digest = sha256(canonicalBody)
        val id = summaryId(rawId, extractorVersion, schema, digest)
        val path = recordPath(id)
        if (!Files.exists(path)) {
            Files.createDirectories(root)
            forceWrite(path, listOf(SpoolState.JOURNALED.name, encode(canonicalBody)).joinToString("|").toByteArray())
        }
        return id
    }

    /** Stages a schema-generated summary whose body can later be recovered into ordinary storage. */
    fun stageStructuralSummary(
        rawId: ByteArray,
        extractorVersion: Int,
        schema: ByteArray,
        summary: GeneratedStructuralSummary,
    ): String = stage(rawId, extractorVersion, schema, GeneratedRecordCodec.encode(summary))

    fun replay(import: (String, ByteArray) -> Unit) {
        Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".tbsummary") }.forEach { path ->
                val id = path.fileName.toString().removeSuffix(".tbsummary")
                val fields = Files.readString(path).trim().split('|', limit = 2)
                if (fields.size != 2) return@forEach
                if (fields[0] == SpoolState.RETIRED.name) return@forEach
                val body = decode(fields[1])
                import(id, body)
                forceWrite(path, "${SpoolState.ACKNOWLEDGED.name}|${fields[1]}".toByteArray())
                forceWrite(path, "${SpoolState.RETIRED.name}|${fields[1]}".toByteArray())
            }
        }
    }

    fun replayToTarget(
                importer: TargetSegmentSummaryImporter,
                crashInjector: SummaryImportCrashInjector? = null,
            ) {
                if (!Files.isDirectory(root)) return
                Files.list(root).use { paths ->
                    paths.filter { it.fileName.toString().endsWith(".tbsummary") }.forEach { path ->
                        val id = path.fileName.toString().removeSuffix(".tbsummary")
                        val fields = Files.readString(path).trim().split('|', limit = 2)
                        if (fields.size != 2 || fields[0] == SpoolState.RETIRED.name) return@forEach
                        importer.import(id, id, decode(fields[1]), crashInjector)
                        forceWrite(path, "${SpoolState.ACKNOWLEDGED.name}|${fields[1]}".toByteArray())
                        forceWrite(path, "${SpoolState.RETIRED.name}|${fields[1]}".toByteArray())
                    }
                }
            }

    fun isRetired(id: String): Boolean = Files.readString(recordPath(id)).startsWith(SpoolState.RETIRED.name)
    private fun recordPath(id: String): Path = root.resolve("$id.tbsummary")
    private fun summaryId(raw: ByteArray, version: Int, schema: ByteArray, digest: ByteArray): String =
        encode(sha256("tracebox-summary-v1".toByteArray() + raw + version.toLittleEndian() + schema + digest))
    private fun Int.toLittleEndian(): ByteArray = byteArrayOf(toByte(), (this shr 8).toByte(), (this shr 16).toByte(), (this shr 24).toByte())
    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}

        /** Location persisted only after the target's forced append and immutable seal. */
        data class SummaryImportAcknowledgement(
            val sourceSpoolId: String,
            val targetSegmentId: ByteArray,
            val offset: Long,
            val summaryId: String,
        ) {
            init { require(targetSegmentId.size == PersistedSegmentIdentity.ID_SIZE) }
        }

        /** Fault injection is intentionally limited to the acknowledgement boundary. */
        fun interface SummaryImportCrashInjector {
            fun afterTargetAppendBeforeAcknowledgement(): Boolean
        }

        /**
         * Imports one summary into a target segment. Recovery finds an already-appended payload by its
         * summary ID, then creates the missing durable acknowledgement before source retirement.
         */
        class TargetSegmentSummaryImporter(
            private val acknowledgementRoot: Path,
            private val targetPath: Path,
            private val target: SegmentWriter,
        ) {
            fun import(
                sourceSpoolId: String,
                summaryId: String,
                canonicalBody: ByteArray,
                crashInjector: SummaryImportCrashInjector? = null,
            ): SummaryImportAcknowledgement {
                val existing = acknowledgement(sourceSpoolId)
                if (existing != null) return existing
                val idBytes = decode(summaryId)
                require(idBytes.size == PersistedSegmentIdentity.ID_SIZE)
                val recovered = SegmentWriter.recover(targetPath, repair = false)
                val matching = recovered.frames.firstOrNull {
                    it.recordType == STRUCTURAL_SUMMARY_RECORD_TYPE &&
                        it.payload.size >= PersistedSegmentIdentity.ID_SIZE &&
                        it.payload.copyOfRange(0, PersistedSegmentIdentity.ID_SIZE).contentEquals(idBytes)
                }
                val offset = if (matching != null) {
                    matching.offset
                } else {
                    when (target.append(
                        STRUCTURAL_SUMMARY_RECORD_TYPE,
                        PolicyTaggedRecord(
                            categoryMask = STRUCTURAL_SUMMARY_CATEGORY,
                            acceptedEpoch = recovered.header.policyGeneration,
                            priority = RecordPriority.CRASH_ANR,
                            payload = idBytes + canonicalBody,
                        ),
                    )) {
                        is SegmentAppendResult.Appended -> {
                            target.seal()
                            SegmentWriter.recover(targetPath, repair = false).frames.last().offset
                        }
                        is SegmentAppendResult.Dropped -> throw IllegalStateException("summary import denied by target policy")
                        is SegmentAppendResult.DroppedQuota -> throw SegmentException.Quota
                    }
                }
                if (crashInjector?.afterTargetAppendBeforeAcknowledgement() == false) {
                    throw SummaryImportInterrupted
                }
                val targetId = SegmentWriter.recover(targetPath, repair = false).header.identity.segmentId
                val acknowledgement = SummaryImportAcknowledgement(sourceSpoolId, targetId, offset, summaryId)
                Files.createDirectories(acknowledgementRoot)
                forceWrite(acknowledgementPath(sourceSpoolId), encodeAcknowledgement(acknowledgement).toByteArray())
                return acknowledgement
            }

            fun acknowledgement(sourceSpoolId: String): SummaryImportAcknowledgement? {
                val path = acknowledgementPath(sourceSpoolId)
                if (!Files.isRegularFile(path)) return null
                val fields = try { Files.readString(path).trim().split('|') } catch (_: java.io.IOException) { return null }
                if (fields.size != 4) return null
                return try {
                    SummaryImportAcknowledgement(fields[0], decode(fields[1]), fields[2].toLong(), fields[3])
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

            private fun acknowledgementPath(sourceSpoolId: String): Path =
                acknowledgementRoot.resolve("$sourceSpoolId.tbimportack")

            private fun encodeAcknowledgement(value: SummaryImportAcknowledgement): String =
                listOf(value.sourceSpoolId, encode(value.targetSegmentId), value.offset, value.summaryId).joinToString("|")

            private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
            private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

            private companion object {
                const val STRUCTURAL_SUMMARY_RECORD_TYPE = 1
                const val STRUCTURAL_SUMMARY_CATEGORY = 1L
            }
        }

        /** Simulated process death used only by the crash-recovery import test. */
        data object SummaryImportInterrupted : IllegalStateException()

        sealed interface GeneratedRecordAppendResult {
            data class Appended(val sequence: Long) : GeneratedRecordAppendResult
            data class Dropped(val reason: GateResult) : GeneratedRecordAppendResult
            data class DroppedQuota(val priority: RecordPriority) : GeneratedRecordAppendResult
            data object Ignored : GeneratedRecordAppendResult
        }

        /**
         * The sole generated-record-to-segment adapter. It accepts generated values only, applies the
         * current writer gate at construction and append, and exposes a bounded last result for hooks.
         */
        class GeneratedRecordSegmentAdapter(
            private val writer: SegmentWriter,
            private val policyGate: WriterPolicyGate,
        ) : Diagnostics {
            @Volatile private var latest: GeneratedRecordAppendResult = GeneratedRecordAppendResult.Ignored

            override fun eventEnabled(eventId: GeneratedEventId): Boolean {
                val descriptor = descriptor(eventId)
                return when (val accepted = policyGate.accept(descriptor.category, descriptor.priority, byteArrayOf())) {
                    is GateAcceptance.Accepted -> true
                    is GateAcceptance.Rejected -> {
                        latest = GeneratedRecordAppendResult.Dropped(accepted.reason)
                        false
                    }
                }
            }

            override fun record(value: GeneratedRecord, context: DiagnosticContext?) {
                val descriptor = descriptor(value.eventId)
                val payload = encode(value)
                latest = when (val accepted = policyGate.accept(descriptor.category, descriptor.priority, payload)) {
                    is GateAcceptance.Rejected -> GeneratedRecordAppendResult.Dropped(accepted.reason)
                    is GateAcceptance.Accepted -> when (val appended = writer.append(value.eventId.stableId, accepted.record)) {
                        is SegmentAppendResult.Appended -> GeneratedRecordAppendResult.Appended(appended.sequence)
                        is SegmentAppendResult.Dropped -> GeneratedRecordAppendResult.Dropped(appended.reason)
                        is SegmentAppendResult.DroppedQuota -> GeneratedRecordAppendResult.DroppedQuota(appended.priority)
                    }
                }
            }

            fun latestResult(): GeneratedRecordAppendResult = latest

            private fun descriptor(eventId: GeneratedEventId): Descriptor = when (eventId) {
                GeneratedEventId.STRUCTURALSUMMARY -> Descriptor(1L, RecordPriority.CRASH_ANR)
                GeneratedEventId.EMERGENCYRECORD -> Descriptor(2L, RecordPriority.CRASH_ANR)
                GeneratedEventId.BREADCRUMB -> Descriptor(4L, RecordPriority.BREADCRUMB)
                GeneratedEventId.HANDLEDERROR -> Descriptor(8L, RecordPriority.HANDLED_ERROR)
            }

            private fun encode(value: GeneratedRecord): ByteArray = GeneratedRecordCodec.encode(value)

            private data class Descriptor(val category: Long, val priority: RecordPriority)
        }

        /** Storage-backed installation seam for the bounded JVM wrapper. */
        class JvmCaptureStorageAdapter(private val records: GeneratedRecordSegmentAdapter) {
            fun install(previous: Thread.UncaughtExceptionHandler?, policy: JvmCapturePolicy = JvmCapturePolicy()): Thread.UncaughtExceptionHandler =
                TraceboxUncaughtExceptionHandler(previous, policy) { captured ->
                    val first = captured.causes.firstOrNull()
                    GeneratedDiagnostics.handledError(
                        records,
                        kind = (first?.type?.hashCode() ?: 0).toUInt(),
                        frame_count = (first?.frames?.size ?: 0).toUShort(),
                    )
                }

            fun latestResult(): GeneratedRecordAppendResult = records.latestResult()
        }

        sealed interface EmergencyIngestionResult {
            data class Ingested(val sequence: Long) : EmergencyIngestionResult
            data class Dropped(val reason: GateResult) : EmergencyIngestionResult
            data object InvalidOrIncomplete : EmergencyIngestionResult
        }

        /** Startup-only reader for the Phase 0 slot; it never participates in the signal-handler path. */
        class EmergencyStartupIngestor(
            private val slot: Path,
            private val records: GeneratedRecordSegmentAdapter,
        ) {
            fun ingest(): EmergencyIngestionResult {
                val bytes = try { Files.readAllBytes(slot) } catch (_: java.io.IOException) { return EmergencyIngestionResult.InvalidOrIncomplete }
                val record = decodeEmergency(bytes) ?: return EmergencyIngestionResult.InvalidOrIncomplete
                GeneratedDiagnostics.emergencyRecord(
                    records,
                    record.slotSequence.toULong(),
                    record.policyEpoch.toULong(),
                    record.signalNumber,
                    record.signalCode,
                    record.processRole.toUInt(),
                    record.threadRole.toUInt(),
                    record.flags.toULong(),
                )
                return when (val result = records.latestResult()) {
                    is GeneratedRecordAppendResult.Appended -> {
                        forceWrite(slot, ByteArray(EMERGENCY_RECORD_SIZE))
                        EmergencyIngestionResult.Ingested(result.sequence)
                    }
                    is GeneratedRecordAppendResult.Dropped -> EmergencyIngestionResult.Dropped(result.reason)
                    is GeneratedRecordAppendResult.DroppedQuota -> EmergencyIngestionResult.Dropped(GateResult.Denied)
                    GeneratedRecordAppendResult.Ignored -> EmergencyIngestionResult.InvalidOrIncomplete
                }
            }

            private fun decodeEmergency(bytes: ByteArray): EmergencyFields? {
                if (bytes.size != EMERGENCY_RECORD_SIZE ||
                    !bytes.copyOfRange(0, 8).contentEquals("TBEMERG1".toByteArray()) ||
                    readInt(bytes, 8) != 1 || readInt(bytes, 12) != EMERGENCY_RECORD_SIZE ||
                    readLong(bytes, 248) != EMERGENCY_COMPLETION ||
                    CRC32C().also { it.update(bytes, 0, 244) }.value.toInt() != readInt(bytes, 244)
                ) return null
                return EmergencyFields(
                    readLong(bytes, 48), readLong(bytes, 56), readInt(bytes, 80), readInt(bytes, 84),
                    readInt(bytes, 112), readInt(bytes, 116), readLong(bytes, 120),
                )
            }

            private fun readInt(bytes: ByteArray, offset: Int): Int =
                java.nio.ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            private fun readLong(bytes: ByteArray, offset: Int): Long =
                java.nio.ByteBuffer.wrap(bytes, offset, Long.SIZE_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN).long

            private data class EmergencyFields(
                val slotSequence: Long, val policyEpoch: Long, val signalNumber: Int, val signalCode: Int,
                val processRole: Int, val threadRole: Int, val flags: Long,
            )

            private companion object {
                const val EMERGENCY_RECORD_SIZE = 256
                const val EMERGENCY_COMPLETION = 0x5442454d434f4d50L
            }
        }
private fun forceWrite(path: Path, bytes: ByteArray) {
    FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use {
        it.write(java.nio.ByteBuffer.wrap(bytes))
        it.force(true)
    }
}
