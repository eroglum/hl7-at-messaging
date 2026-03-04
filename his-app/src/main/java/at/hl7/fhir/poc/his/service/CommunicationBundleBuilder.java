package at.hl7.fhir.poc.his.service;

import at.hl7.fhir.poc.his.model.ReceivedRequest;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r5.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * Builds ATMessagingBundle with Communication resource for text message
 * responses.
 * Used when replying to CommunicationRequest messages with a text response.
 */
@Service
@Slf4j
public class CommunicationBundleBuilder {

    @Value("${his.endpoint.name:Hospital Information System}")
    private String hisEndpointName;

    @Value("${his.endpoint.address:matrix:@his_user:matrix.local}")
    private String hisEndpointAddress;

    @Value("${gp.endpoint.name:General Practitioner}")
    private String gpEndpointName;

    @Value("${gp.endpoint.address:matrix:@gp_user:matrix.local}")
    private String gpEndpointAddress;

    @Value("${pharmacy.endpoint.name:Apotheke am Hauptplatz}")
    private String pharmacyEndpointName;

    @Value("${pharmacy.endpoint.address:matrix:@pharmacy_user:matrix.local}")
    private String pharmacyEndpointAddress;

    /**
     * Creates an ATMessagingBundle with Communication as a response to a
     * CommunicationRequest.
     *
     * @param originalRequest  The original request being responded to
     * @param messageText      The text message content
     * @param originalBundleId The bundle ID of the original request (for
     *                         MessageHeader.response)
     * @return The FHIR Bundle with Communication
     */
    public Bundle createCommunicationResponseBundle(ReceivedRequest originalRequest,
            String messageText,
            String originalBundleId) {
        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(Bundle.BundleType.MESSAGE);
        bundle.setTimestamp(new Date());

        // Add meta profile for ATMessagingBundle
        bundle.getMeta()
                .addProfile("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-bundle");

        // Create endpoints
        Endpoint sourceEndpoint = createEndpoint(hisEndpointName, hisEndpointAddress);
        Endpoint destinationEndpoint = createEndpoint(gpEndpointName, gpEndpointAddress);

        // Create practitioners
        Practitioner sender = createSenderPractitioner();
        Practitioner receiver = createReceiverPractitioner();

        // Create patient from original request data
        Patient patient = createPatientFromRequest(originalRequest);

        // Create Communication resource
        Communication communication = createCommunication(patient, messageText, sender, receiver);

        // Create MessageHeader with response reference
        MessageHeader messageHeader = createMessageHeader(
                sourceEndpoint, destinationEndpoint, sender, receiver,
                communication, patient, originalBundleId);

        // Add entries to bundle (MessageHeader must be first)
        addEntry(bundle, messageHeader);
        addEntry(bundle, sourceEndpoint);
        addEntry(bundle, destinationEndpoint);
        addEntry(bundle, sender);
        addEntry(bundle, receiver);
        addEntry(bundle, patient);
        addEntry(bundle, communication);

        log.info("Created ATMessagingBundle with Communication response, {} entries", bundle.getEntry().size());
        return bundle;
    }

    /**
     * Creates an ATMessagingBundle with Communication for sending a standalone
     * message to Pharmacy.
     *
     * @param patientName      Patient name
     * @param patientBirthDate Patient birth date (yyyy-MM-dd or null)
     * @param messageText      The text message content
     * @return The FHIR Bundle with Communication
     */
    public Bundle createPharmacyCommunicationBundle(String patientName, String patientBirthDate, String messageText) {
        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(Bundle.BundleType.MESSAGE);
        bundle.setTimestamp(new Date());

        bundle.getMeta()
                .addProfile("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-bundle");

        Endpoint sourceEndpoint = createEndpoint(hisEndpointName, hisEndpointAddress);
        Endpoint destinationEndpoint = createEndpoint(pharmacyEndpointName, pharmacyEndpointAddress);

        Practitioner sender = createSenderPractitioner();
        Practitioner receiver = createPharmacyPractitioner();

        Patient patient = new Patient();
        patient.setId(UUID.randomUUID().toString());
        patient.getMeta().addProfile("http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-patient");
        if (patientName != null && !patientName.isBlank()) {
            HumanName name = patient.addName();
            String[] parts = patientName.split(" ");
            name.setFamily(parts[parts.length - 1]);
            for (int i = 0; i < parts.length - 1; i++) {
                name.addGiven(parts[i]);
            }
        }
        if (patientBirthDate != null && !patientBirthDate.isBlank()) {
            patient.setBirthDateElement(new DateType(patientBirthDate));
        }

        Communication communication = createCommunication(patient, messageText, sender, receiver);

        MessageHeader messageHeader = createMessageHeader(
                sourceEndpoint, destinationEndpoint, sender, receiver,
                communication, patient, null);
        // Override destination name for pharmacy
        messageHeader.getDestination().getFirst().setName(pharmacyEndpointName);

        addEntry(bundle, messageHeader);
        addEntry(bundle, sourceEndpoint);
        addEntry(bundle, destinationEndpoint);
        addEntry(bundle, sender);
        addEntry(bundle, receiver);
        addEntry(bundle, patient);
        addEntry(bundle, communication);

        log.info("Created ATMessagingBundle with Communication for Pharmacy, {} entries", bundle.getEntry().size());
        return bundle;
    }

    private Patient createPatientFromRequest(ReceivedRequest request) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID().toString());
        patient.getMeta().addProfile("http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-patient");

        // Parse name from request
        if (request.getPatientName() != null) {
            HumanName name = patient.addName();
            String[] nameParts = request.getPatientName().split(" ");
            if (nameParts.length >= 2) {
                name.setFamily(nameParts[nameParts.length - 1]);
                for (int i = 0; i < nameParts.length - 1; i++) {
                    name.addGiven(nameParts[i]);
                }
            } else {
                name.setFamily(request.getPatientName());
            }
            name.setUse(HumanName.NameUse.OFFICIAL);
        }

        // Set birth date if available
        if (request.getPatientBirthDate() != null) {
            patient.setBirthDateElement(new DateType(request.getPatientBirthDate()));
        }

        return patient;
    }

    private Communication createCommunication(Patient patient, String messageText,
            Practitioner sender, Practitioner receiver) {
        Communication communication = new Communication();
        communication.setId(UUID.randomUUID().toString());

        // Add profile for ATMessagingCommunication
        communication.getMeta()
                .addProfile("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-communication");

        communication.setStatus(Enumerations.EventStatus.COMPLETED);

        // Subject - the patient
        communication.setSubject(new Reference("urn:uuid:" + patient.getId())
                .setDisplay(getPatientDisplayName(patient)));

        // Sender
        communication.setSender(new Reference("urn:uuid:" + sender.getId())
                .setDisplay("Dr. Selina Mayer"));

        // Recipient
        communication.addRecipient(new Reference("urn:uuid:" + receiver.getId())
                .setDisplay("Dr. Johann Huber"));

        // Sent timestamp
        communication.setSent(new Date());

        // Payload - the message text
        Communication.CommunicationPayloadComponent payload = communication.addPayload();
        Attachment attachment = new Attachment();
        attachment.setContentType("text/plain");
        attachment.setLanguage("de");
        attachment.setData(messageText.getBytes(StandardCharsets.UTF_8));
        attachment.setTitle("Response Message");
        attachment.setCreation(new Date());
        payload.setContent(attachment);

        return communication;
    }

    private MessageHeader createMessageHeader(Endpoint source, Endpoint destination,
            Practitioner sender, Practitioner receiver,
            Communication communication, Patient patient,
            String originalBundleId) {
        MessageHeader messageHeader = new MessageHeader();
        messageHeader.setId(UUID.randomUUID().toString());

        // Meta profile for ATMessagingMessageHeader
        messageHeader.getMeta().addProfile(
                "http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-message-header");

        // Event type - using "status" for response/communication messages
        messageHeader.setEvent(new Coding()
                .setSystem("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/CodeSystem/at-messaging-event-type")
                .setCode("status")
                .setDisplay("Status/Response"));

        // Definition - canonical URL to ATMessagingCommunicationMessageDefinition
        messageHeader.setDefinition(
                "http://fhir.hl7.at/fhir/ATMessaging/0.1.0/MessageDefinition/at-messaging-communication-message");

        // Response - reference to the original request bundle
        if (originalBundleId != null && !originalBundleId.isEmpty()) {
            MessageHeader.MessageHeaderResponseComponent response = messageHeader.getResponse();
            response.setIdentifier(new Identifier().setValue(originalBundleId));
            response.setCode(MessageHeader.ResponseType.OK);
        }

        // Source
        MessageHeader.MessageSourceComponent sourceComponent = messageHeader.getSource();
        sourceComponent.setEndpoint(new Reference("urn:uuid:" + source.getId()));
        sourceComponent.setName(hisEndpointName);
        sourceComponent.setSoftware("at.hl7.fhir.poc.his");
        sourceComponent.setVersion("0.1.0");
        sourceComponent.setContact(new ContactPoint()
                .setSystem(ContactPoint.ContactPointSystem.EMAIL)
                .setValue("dummy.his.support@example.com"));

        // Destination
        MessageHeader.MessageDestinationComponent destComponent = messageHeader.addDestination();
        destComponent.setEndpoint(new Reference("urn:uuid:" + destination.getId()));
        destComponent.setName(gpEndpointName);
        destComponent.setReceiver(new Reference("urn:uuid:" + receiver.getId())
                .setDisplay("Dr. Johann Huber"));

        // Sender and author
        messageHeader.setSender(new Reference("urn:uuid:" + sender.getId()));
        messageHeader.setAuthor(new Reference("urn:uuid:" + sender.getId()));

        // Focus - the Communication and Patient
        messageHeader.addFocus(new Reference("urn:uuid:" + communication.getId())
                .setType("Communication"));
        messageHeader.addFocus(new Reference("urn:uuid:" + patient.getId())
                .setType("Patient"));

        return messageHeader;
    }

    private Endpoint createEndpoint(String name, String address) {
        Endpoint endpoint = new Endpoint();
        endpoint.setId(UUID.randomUUID().toString());
        endpoint.getMeta()
                .addProfile("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-endpoint");
        endpoint.setStatus(Endpoint.EndpointStatus.ACTIVE);
        endpoint.setName(name);
        endpoint.setAddress(address);

        endpoint.addConnectionType(new CodeableConcept()
                .addCoding(new Coding()
                        .setSystem("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/CodeSystem/at-messaging-endpoint-type")
                        .setCode("matrix")
                        .setDisplay("The message is transported over the Matrix protocol.")));

        return endpoint;
    }

    private Practitioner createSenderPractitioner() {
        Practitioner practitioner = new Practitioner();
        practitioner.setId(UUID.randomUUID().toString());
        practitioner.getMeta()
                .addProfile("http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-practitioner");

        HumanName name = practitioner.addName();
        name.setFamily("Mayer");
        name.addPrefix("Dr.");
        name.addGiven("Selina");

        practitioner.addIdentifier()
                .setSystem("urn:oid:1.2.40.0.10.1.4.3.2")
                .setValue("GP-54321");

        return practitioner;
    }

    private Practitioner createReceiverPractitioner() {
        Practitioner practitioner = new Practitioner();
        practitioner.setId(UUID.randomUUID().toString());
        practitioner.getMeta()
                .addProfile("http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-practitioner");

        HumanName name = practitioner.addName();
        name.setFamily("Huber");
        name.addPrefix("Dr.");
        name.addGiven("Johann");

        practitioner.addIdentifier()
                .setSystem("urn:oid:1.2.40.0.10.1.4.3.2")
                .setValue("GP-12345");

        return practitioner;
    }

    private Practitioner createPharmacyPractitioner() {
        Practitioner practitioner = new Practitioner();
        practitioner.setId(UUID.randomUUID().toString());
        practitioner.getMeta()
                .addProfile("http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-practitioner");

        HumanName name = practitioner.addName();
        name.setFamily("Fischer");
        name.addPrefix("Mag.pharm.");
        name.addGiven("Elena");

        return practitioner;
    }

    private void addEntry(Bundle bundle, Resource resource) {
        Bundle.BundleEntryComponent entry = bundle.addEntry();
        entry.setFullUrl("urn:uuid:" + resource.getId());
        entry.setResource(resource);
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
}
