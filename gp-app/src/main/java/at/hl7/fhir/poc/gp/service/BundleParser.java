package at.hl7.fhir.poc.gp.service;

import at.hl7.fhir.poc.gp.model.ReceivedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r5.model.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Parses received ATMessagingBundle and extracts resources.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BundleParser {

    private final FhirService fhirService;
    private final List<ReceivedMessage> receivedMessages = Collections.synchronizedList(new ArrayList<>());

    public void parseAndSaveBundle(String bundleJson) {
        try {
            Resource resource = fhirService.parseResource(bundleJson);

            if (!(resource instanceof Bundle bundle)) {
                log.warn("Received resource is not a Bundle");
                return;
            }

            if (bundle.getType() != Bundle.BundleType.MESSAGE) {
                log.warn("Received Bundle is not of type 'message'");
                return;
            }

            log.info("Parsing message bundle with {} entries", bundle.getEntry().size());

            // Extract MessageHeader first to check event type
            MessageHeader messageHeader = null;
            for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                if (entry.getResource() instanceof MessageHeader mh) {
                    messageHeader = mh;
                    break;
                }
            }

            // Only process messages with event type "document"
            if (messageHeader == null) {
                log.warn("Bundle does not contain MessageHeader, skipping");
                return;
            }

            String eventCode = getEventCode(messageHeader);
            // Accept "document", "status" (communication), and "communication" (pharmacy)
            // messages
            if (!"document".equals(eventCode) && !"status".equals(eventCode) && !"communication".equals(eventCode)) {
                log.debug(
                        "Skipping message with event type '{}', GP app only processes 'document', 'status', and 'communication' messages",
                        eventCode);
                return;
            }

            // Extract remaining resources from bundle
            Patient patient = null;
            DocumentReference documentReference = null;
            Communication communication = null;
            MedicationDispense medicationDispense = null;
            Medication medication = null;
            List<Endpoint> endpoints = new ArrayList<>();
            List<Practitioner> practitioners = new ArrayList<>();
            List<PractitionerRole> practitionerRoles = new ArrayList<>();
            Organization organization = null;

            for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                Resource entryResource = entry.getResource();
                if (entryResource instanceof Patient p) {
                    patient = p;
                } else if (entryResource instanceof DocumentReference dr) {
                    documentReference = dr;
                } else if (entryResource instanceof Communication comm) {
                    communication = comm;
                } else if (entryResource instanceof MedicationDispense md) {
                    medicationDispense = md;
                } else if (entryResource instanceof Medication med) {
                    medication = med;
                } else if (entryResource instanceof Endpoint ep) {
                    endpoints.add(ep);
                } else if (entryResource instanceof Practitioner pr) {
                    practitioners.add(pr);
                } else if (entryResource instanceof PractitionerRole prRole) {
                    practitionerRoles.add(prRole);
                } else if (entryResource instanceof Organization org) {
                    organization = org;
                }
            }

            // Create received message record
            ReceivedMessage receivedMessage = new ReceivedMessage();
            receivedMessage.setId(UUID.randomUUID().toString());
            receivedMessage.setReceivedAt(new Date());
            receivedMessage.setBundleJson(bundleJson);

            // messageHeader is guaranteed non-null at this point (checked earlier)
            receivedMessage.setEventCode(eventCode);
            receivedMessage.setSourceName(getSourceName(messageHeader));

            // Get source details
            if (messageHeader.hasSource()) {
                MessageHeader.MessageSourceComponent source = messageHeader.getSource();
                if (source.hasSoftware()) {
                    receivedMessage.setSourceSoftware(source.getSoftware());
                }
                if (source.hasVersion()) {
                    receivedMessage.setSourceVersion(source.getVersion());
                }
                if (source.hasContact() && source.getContact().hasValue()) {
                    receivedMessage.setSourceContact(source.getContact().getValue());
                }
            }

            // Get subject/reason
            if (messageHeader.hasReason() && !messageHeader.getReason().isEmpty()) {
                receivedMessage.setSubject(messageHeader.getReason().getText());
            }

            // Extract response reference if this is a response to a previous message
            if (messageHeader.hasResponse() && messageHeader.getResponse().hasIdentifier()) {
                String responseToId = messageHeader.getResponse().getIdentifier().getValue();
                receivedMessage.setResponseToRequestId(responseToId);
                log.debug("Message is a response to request: {}", responseToId);
            }

            // Get sender info from PractitionerRole
            if (messageHeader.hasSender()) {
                String senderRef = messageHeader.getSender().getReference();
                PractitionerRole senderRole = findPractitionerRoleByRef(practitionerRoles, senderRef);
                if (senderRole != null) {
                    // Get practitioner name
                    if (senderRole.hasPractitioner()) {
                        String practRef = senderRole.getPractitioner().getReference();
                        Practitioner senderPract = findPractitionerByRef(practitioners, practRef);
                        if (senderPract != null) {
                            receivedMessage.setSenderName(getPractitionerDisplayName(senderPract));
                        }
                    }
                }
            }

            // Get organization info
            if (organization != null) {
                receivedMessage.setSenderOrganization(organization.hasName() ? organization.getName() : null);
                if (organization.hasType() && !organization.getType().isEmpty()) {
                    CodeableConcept type = organization.getType().getFirst();
                    if (type.hasCoding() && !type.getCoding().isEmpty()) {
                        Coding coding = type.getCodingFirstRep();
                        receivedMessage.setSenderOrganizationType(
                                coding.hasDisplay() ? coding.getDisplay() : coding.getCode());
                    }
                }
            }

            if (patient != null) {
                receivedMessage.setPatientName(getPatientDisplayName(patient));
                receivedMessage.setPatientBirthDate(
                        patient.hasBirthDate() ? patient.getBirthDateElement().getValueAsString() : null);
            }

            if (documentReference != null) {
                receivedMessage.setDocumentTitle(documentReference.getDescription());
                receivedMessage.setDocumentType(getDocumentType(documentReference));
                receivedMessage.setDocumentCategory(getDocumentCategory(documentReference));
                receivedMessage.setDocumentDate(documentReference.getDate());
                extractDocumentContent(documentReference, receivedMessage);

                // Get document author
                if (documentReference.hasAuthor() && !documentReference.getAuthor().isEmpty()) {
                    String authorRef = documentReference.getAuthor().getFirst().getReference();
                    PractitionerRole authorRole = findPractitionerRoleByRef(practitionerRoles, authorRef);
                    if (authorRole != null && authorRole.hasPractitioner()) {
                        String practRef = authorRole.getPractitioner().getReference();
                        Practitioner authorPract = findPractitionerByRef(practitioners, practRef);
                        if (authorPract != null) {
                            receivedMessage.setDocumentAuthorName(getPractitionerDisplayName(authorPract));
                        }
                    }
                }
            }

            // Extract Communication content (for status/response messages)
            if (communication != null) {
                receivedMessage.setCommunicationSent(communication.getSent());
                extractCommunicationContent(communication, receivedMessage);

                // Get sender name from Communication if not already set
                if (receivedMessage.getSenderName() == null && communication.hasSender()) {
                    String senderRef = communication.getSender().getReference();
                    Practitioner senderPract = findPractitionerByRef(practitioners, senderRef);
                    if (senderPract != null) {
                        receivedMessage.setSenderName(getPractitionerDisplayName(senderPract));
                    } else if (communication.getSender().hasDisplay()) {
                        receivedMessage.setSenderName(communication.getSender().getDisplay());
                    }
                }
            }

            // Extract MedicationDispense content (for pharmacy messages)
            if (medicationDispense != null) {
                extractMedicationDispenseContent(medicationDispense, medication, receivedMessage);
            }

            // Save to local FHIR server
            Bundle savedBundle = fhirService.saveBundle(bundle);
            if (savedBundle != null) {
                receivedMessage.setFhirBundleId(savedBundle.getIdElement().getIdPart());
                log.info("Saved message bundle to FHIR server with ID: {}",
                        savedBundle.getIdElement().getIdPart());
            }

            // Add to received messages list
            receivedMessages.add(receivedMessage);
            log.info("Processed message: {} from {}", receivedMessage.getSubject(),
                    receivedMessage.getSourceName());

        } catch (Exception e) {
            log.error("Error parsing message bundle", e);
        }
    }

    public List<ReceivedMessage> getReceivedMessages() {
        synchronized (receivedMessages) {
            // Return newest first
            List<ReceivedMessage> sorted = new ArrayList<>(receivedMessages);
            sorted.sort((a, b) -> b.getReceivedAt().compareTo(a.getReceivedAt()));
            return sorted;
        }
    }

    public ReceivedMessage getMessage(String id) {
        synchronized (receivedMessages) {
            return receivedMessages.stream()
                    .filter(m -> m.getId().equals(id))
                    .findFirst()
                    .orElse(null);
        }
    }

    public int getMessageCount() {
        return receivedMessages.size();
    }

    private String getEventCode(MessageHeader messageHeader) {
        if (messageHeader.hasEvent() && messageHeader.getEvent() instanceof Coding coding) {
            return coding.getCode();
        }
        return "unknown";
    }

    private String getSourceName(MessageHeader messageHeader) {
        if (messageHeader.hasSource() && messageHeader.getSource().hasName()) {
            return messageHeader.getSource().getName();
        }
        return "Unknown Source";
    }

    private String getPatientDisplayName(Patient patient) {
        if (patient.hasName() && !patient.getName().isEmpty()) {
            HumanName name = patient.getName().getFirst();
            StringBuilder display = new StringBuilder();
            if (name.hasGiven()) {
                for (StringType given : name.getGiven()) {
                    display.append(given.getValue()).append(" ");
                }
            }
            if (name.hasFamily()) {
                display.append(name.getFamily());
            }
            return display.toString().trim();
        }
        return "Unknown Patient";
    }

    private String getDocumentType(DocumentReference documentReference) {
        if (documentReference.hasType() && documentReference.getType().hasCoding()) {
            Coding coding = documentReference.getType().getCodingFirstRep();
            return coding.hasDisplay() ? coding.getDisplay() : coding.getCode();
        }
        return "Document";
    }

    private String getDocumentCategory(DocumentReference documentReference) {
        if (documentReference.hasCategory() && !documentReference.getCategory().isEmpty()) {
            CodeableConcept category = documentReference.getCategory().getFirst();
            if (category.hasCoding() && !category.getCoding().isEmpty()) {
                Coding coding = category.getCodingFirstRep();
                return coding.hasDisplay() ? coding.getDisplay() : coding.getCode();
            }
        }
        return null;
    }

    private String getPractitionerDisplayName(Practitioner practitioner) {
        if (practitioner.hasName() && !practitioner.getName().isEmpty()) {
            HumanName name = practitioner.getName().getFirst();
            StringBuilder display = new StringBuilder();
            if (name.hasPrefix()) {
                for (StringType prefix : name.getPrefix()) {
                    display.append(prefix.getValue()).append(" ");
                }
            }
            if (name.hasGiven()) {
                for (StringType given : name.getGiven()) {
                    display.append(given.getValue()).append(" ");
                }
            }
            if (name.hasFamily()) {
                display.append(name.getFamily());
            }
            return display.toString().trim();
        }
        return "Unknown Practitioner";
    }

    private PractitionerRole findPractitionerRoleByRef(List<PractitionerRole> roles, String reference) {
        if (reference == null)
            return null;
        String id = reference.startsWith("urn:uuid:") ? reference.substring(9) : reference;
        return roles.stream()
                .filter(r -> r.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    private Practitioner findPractitionerByRef(List<Practitioner> practitioners, String reference) {
        if (reference == null)
            return null;
        String id = reference.startsWith("urn:uuid:") ? reference.substring(9) : reference;
        return practitioners.stream()
                .filter(p -> p.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * Extracts document content from DocumentReference and populates the
     * ReceivedMessage.
     * Handles both PDF and text content types.
     */
    private void extractDocumentContent(DocumentReference documentReference, ReceivedMessage message) {
        if (!documentReference.hasContent() || documentReference.getContent().isEmpty()) {
            return;
        }

        DocumentReference.DocumentReferenceContentComponent content = documentReference.getContent().getFirst();
        if (!content.hasAttachment()) {
            return;
        }

        Attachment attachment = content.getAttachment();
        String contentType = attachment.hasContentType() ? attachment.getContentType() : "text/plain";
        message.setDocumentContentType(contentType);

        // Store filename if available
        if (attachment.hasTitle()) {
            message.setDocumentFilename(attachment.getTitle());
        }

        if (attachment.hasData()) {
            byte[] data = attachment.getData();

            if ("application/pdf".equals(contentType)) {
                // For PDF: store base64 encoded data for download
                message.setDocumentBase64Data(Base64.getEncoder().encodeToString(data));
                message.setDocumentContent(null); // No text content for PDF
            } else {
                // For text: decode and store as string
                message.setDocumentContent(new String(data));
                message.setDocumentBase64Data(null);
            }
        }
    }

    /**
     * Extracts communication content from Communication and populates the
     * ReceivedMessage.
     */
    private void extractCommunicationContent(Communication communication, ReceivedMessage message) {
        if (!communication.hasPayload() || communication.getPayload().isEmpty()) {
            return;
        }

        Communication.CommunicationPayloadComponent payload = communication.getPayload().getFirst();
        if (payload.hasContent() && payload.getContent() instanceof Attachment attachment) {
            if (attachment.hasData()) {
                byte[] data = attachment.getData();
                message.setCommunicationText(new String(data));
            }
        }
    }

    /**
     * Extracts MedicationDispense content from bundle and populates the
     * ReceivedMessage.
     */
    private void extractMedicationDispenseContent(MedicationDispense medicationDispense,
            Medication medication,
            ReceivedMessage message) {
        // Extract medication name
        String medName = null;
        if (medication != null && medication.hasCode()) {
            medName = medication.getCode().hasText() ? medication.getCode().getText()
                    : (medication.getCode().hasCoding() ? medication.getCode().getCodingFirstRep().getDisplay() : null);
        }
        if (medName == null && medicationDispense.hasMedication() && medicationDispense.getMedication().hasConcept()) {
            medName = medicationDispense.getMedication().getConcept().getText();
        }
        message.setMedicationName(medName != null ? medName : "Unknown Medication");

        // Extract dosage instruction
        if (medicationDispense.hasDosageInstruction() && !medicationDispense.getDosageInstruction().isEmpty()) {
            message.setDosageInstruction(medicationDispense.getDosageInstruction().get(0).getText());
        }

        // Extract dispensed quantity
        if (medicationDispense.hasQuantity()) {
            Quantity qty = medicationDispense.getQuantity();
            String qtyStr = qty.hasValue() ? qty.getValue().toPlainString() : "?";
            String unit = qty.hasUnit() ? " " + qty.getUnit() : "";
            message.setDispensedQuantity(qtyStr + unit);
        }

        // Set subject from medication info
        if (message.getSubject() == null || message.getSubject().isEmpty()) {
            message.setSubject("Medication Dispensed: " + message.getMedicationName());
        }
    }
}
