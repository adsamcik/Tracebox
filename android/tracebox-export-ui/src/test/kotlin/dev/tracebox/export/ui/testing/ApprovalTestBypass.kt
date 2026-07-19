package dev.tracebox.export.ui.testing

import dev.tracebox.export.ui.ApprovalIssuer
import dev.tracebox.export.ui.ApprovedPackage
import dev.tracebox.export.ui.RenderedDisclosure

/** Test-source-only bridge. No production source can call the approval issuer except the Activity. */
internal object ApprovalTestBypass {
    fun confirm(rendered: RenderedDisclosure): ApprovedPackage = ApprovalIssuer.afterFreshConfirmation(rendered)
}
