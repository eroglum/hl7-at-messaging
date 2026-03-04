package at.hl7.fhir.poc.gp.model;

import lombok.Data;

import java.util.Date;

@Data
public class ReceivedMessage {
    private String id;
    private Date receivedAt;
    private String eventCode;
    private String sourceName;
    private String sourceSoftware;
    private String sourceVersion;
    private String sourceContact;
    private String subject;
    private String patientName;
    private String patientBirthDate;

    // Document-specific fields (for eventCode "document")
    private String documentTitle;
    private String documentType;
    private String documentCategory;
    private String documentContent;
    private String documentContentType; // "application/pdf" or "text/plain"
    private String documentBase64Data; // Base64-encoded PDF for download
    private String documentFilename; // Original filename
    private Date documentDate;
    private String documentAuthorName;

    // Communication-specific fields (for eventCode "status")
    private String communicationText; // Text content of Communication
    private Date communicationSent; // When the communication was sent

    // Medication-specific fields (for eventCode "communication" with
    // MedicationDispense)
    private String medicationName;
    private String dosageInstruction;
    private String dispensedQuantity;

    // Response reference (if this message is a response to a request)
    private String responseToRequestId; // ID of the original request bundle

    // Sender info
    private String senderName;
    private String senderOrganization;
    private String senderOrganizationType;

    // Bundle reference
    private String fhirBundleId;
    private String bundleJson;

    /**
     * Returns true if this message is a document message.
     */
    public boolean isDocument() {
        return "document".equals(eventCode);
    }

    /**
     * Returns true if this message is a communication/status message.
     */
    public boolean isCommunication() {
        return "status".equals(eventCode);
    }

    /**
     * Returns true if this message contains a MedicationDispense.
     */
    public boolean isMedicationDispense() {
        return medicationName != null && !medicationName.isEmpty();
    }

    /**
     * Returns true if this message is a response to another message.
     */
    public boolean isResponse() {
        return responseToRequestId != null && !responseToRequestId.isEmpty();
    }
}
