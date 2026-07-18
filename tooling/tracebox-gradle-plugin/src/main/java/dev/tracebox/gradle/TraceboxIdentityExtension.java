package dev.tracebox.gradle;

/** Inputs for future AGP variant identity capture tasks. */
public class TraceboxIdentityExtension {
    private String schemaFile = "schema/events.json";
    private String r8MappingFile;
    private String nativeLibrariesDirectory;

    /** Returns the authoritative schema path. */
    public String getSchemaFile() {
        return schemaFile;
    }

    /** Sets the authoritative schema path. */
    public void setSchemaFile(String schemaFile) {
        this.schemaFile = schemaFile;
    }

    /** Optional mapping artifact for non-Android functional use of the plugin. */
    public String getR8MappingFile() {
        return r8MappingFile;
    }

    /** Sets an optional R8/ProGuard mapping artifact. */
    public void setR8MappingFile(String r8MappingFile) {
        this.r8MappingFile = r8MappingFile;
    }

    /** Optional native library directory for non-Android functional use of the plugin. */
    public String getNativeLibrariesDirectory() {
        return nativeLibrariesDirectory;
    }

    /** Sets an optional directory containing native ELF libraries. */
    public void setNativeLibrariesDirectory(String nativeLibrariesDirectory) {
        this.nativeLibrariesDirectory = nativeLibrariesDirectory;
    }
}
