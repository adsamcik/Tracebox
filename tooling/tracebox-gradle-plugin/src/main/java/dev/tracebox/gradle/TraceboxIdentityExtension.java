package dev.tracebox.gradle;

/** Inputs for future AGP variant identity capture tasks. */
public class TraceboxIdentityExtension {
    private String schemaFile = "schema/events.json";

    /** Returns the authoritative schema path. */
    public String getSchemaFile() {
        return schemaFile;
    }

    /** Sets the authoritative schema path. */
    public void setSchemaFile(String schemaFile) {
        this.schemaFile = schemaFile;
    }
}
