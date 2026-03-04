package at.hl7.fhir.poc.gp.service;

import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r5.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * Builds ATMessagingBundle with CommunicationRequest for requesting consult
 * documents from HIS.
 * Uses event type 'request' per AT Messaging IG specification.
 */
@Service
@Slf4j
public class CommunicationRequestBundleBuilder {

        @Value("${gp.endpoint.name:General Practitioner - Dr. Huber}")
        private String gpEndpointName;

        @Value("${gp.endpoint.address:matrix:@gp_user:matrix.local}")
        private String gpEndpointAddress;

        @Value("${his.endpoint.name:Hospital Information System}")
        private String hisEndpointName;

        @Value("${his.endpoint.address:matrix:@his_user:matrix.local}")
        private String hisEndpointAddress;

        @Value("${pharmacy.endpoint.name:Apotheke am Hauptplatz}")
        private String pharmacyEndpointName;

        @Value("${pharmacy.endpoint.address:matrix:@pharmacy_user:matrix.local}")
        private String pharmacyEndpointAddress;

        /**
         * Creates an ATMessagingBundle for requesting a consult document from HIS.
         *
         * @param patient            The patient for whom the consult is requested
         * @param requestDescription Description of what is being requested
         * @return The FHIR Bundle with CommunicationRequest
         */
        public Bundle createCommunicationRequestBundle(Patient patient, String requestDescription) {
                return createCommunicationRequestBundle(patient, requestDescription, "his");
        }

        /**
         * Creates an ATMessagingBundle for requesting a consult document from a
         * specified recipient.
         *
         * @param patient            The patient for whom the consult is requested
         * @param requestDescription Description of what is being requested
         * @param recipient          "his" or "pharmacy"
         * @return The FHIR Bundle with CommunicationRequest
         */
        public Bundle createCommunicationRequestBundle(Patient patient, String requestDescription, String recipient) {
                String destName;
                String destAddress;
                if ("pharmacy".equals(recipient)) {
                        destName = pharmacyEndpointName;
                        destAddress = pharmacyEndpointAddress;
                } else {
                        destName = hisEndpointName;
                        destAddress = hisEndpointAddress;
                }

                Bundle bundle = new Bundle();
                bundle.setId(UUID.randomUUID().toString());
                bundle.setType(Bundle.BundleType.MESSAGE);
                bundle.setTimestamp(new Date());

                // Add meta profile for ATMessagingBundle
                bundle.getMeta().addProfile(
                                "http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-bundle");

                // Create source endpoint (GP - sender)
                Endpoint sourceEndpoint = createEndpoint(gpEndpointName, gpEndpointAddress);

                // Create destination endpoint (recipient)
                Endpoint destinationEndpoint = createEndpoint(destName, destAddress);

                // Create sender Practitioner (Dr. Huber - GP)
                Practitioner sender = createSenderPractitioner();

                // Create receiver Practitioner
                Practitioner receiver = "pharmacy".equals(recipient) ? createPharmacyPractitioner()
                                : createReceiverPractitioner();

                // Assign new UUID to patient for this message
                Patient messagePatient = patient.copy();
                messagePatient.addIdentifier()
                                .setSystem("http://mydummygp.example.com/Identifiers/Patient")
                                .setValue(patient.getId());
                messagePatient.setId(UUID.randomUUID().toString());

                // Create CommunicationRequest
                CommunicationRequest communicationRequest = createCommunicationRequest(
                                messagePatient, requestDescription, sender, receiver);

                // Create MessageHeader
                MessageHeader messageHeader = createMessageHeader(
                                sourceEndpoint, destinationEndpoint, sender, receiver, communicationRequest,
                                messagePatient, destName);

                // Add entries to bundle (MessageHeader must be first)
                addEntry(bundle, messageHeader);
                addEntry(bundle, sourceEndpoint);
                addEntry(bundle, destinationEndpoint);
                addEntry(bundle, sender);
                addEntry(bundle, receiver);
                addEntry(bundle, messagePatient);
                addEntry(bundle, communicationRequest);

                log.info("Created ATMessagingBundle with CommunicationRequest for {}, {} entries", destName,
                                bundle.getEntry().size());
                return bundle;
        }

        private Endpoint createEndpoint(String name, String address) {
                Endpoint endpoint = new Endpoint();
                endpoint.setId(UUID.randomUUID().toString());
                endpoint.getMeta().addProfile(
                                "http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-endpoint");
                endpoint.setStatus(Endpoint.EndpointStatus.ACTIVE);
                endpoint.setName(name);
                endpoint.setAddress(address);

                // Set connection type for Matrix
                endpoint.addConnectionType(new CodeableConcept()
                                .addCoding(new Coding()
                                                .setSystem("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/CodeSystem/at-messaging-endpoint-type")
                                                .setCode("matrix")
                                                .setDisplay("The message is transported over the Matrix protocol.")));

                return endpoint;
        }

        /**
         * Creates the sender Practitioner (Dr. Huber - GP).
         */
        private Practitioner createSenderPractitioner() {
                Practitioner practitioner = new Practitioner();
                practitioner.setId(UUID.randomUUID().toString());
                practitioner.getMeta().addProfile(
                                "http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-practitioner");

                HumanName name = practitioner.addName();
                name.setFamily("Huber");
                name.addPrefix("Dr.");
                name.addGiven("Johann");

                practitioner.addIdentifier()
                                .setSystem("urn:oid:1.2.40.0.10.1.4.3.2")
                                .setValue("GP-12345");

                return practitioner;
        }

        /**
         * Creates the receiver Practitioner (Dr. Mayer - HIS).
         */
        private Practitioner createReceiverPractitioner() {
                Practitioner practitioner = new Practitioner();
                practitioner.setId(UUID.randomUUID().toString());
                practitioner.getMeta().addProfile(
                                "http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-practitioner");

                HumanName name = practitioner.addName();
                name.setFamily("Mayer");
                name.addPrefix("Dr.");
                name.addGiven("Selina");

                practitioner.addIdentifier()
                                .setSystem("urn:oid:1.2.40.0.10.1.4.3.2")
                                .setValue("GP-54321");

                return practitioner;
        }

        /**
         * Creates the Pharmacy Practitioner (Mag.pharm. Elena Fischer).
         */
        private Practitioner createPharmacyPractitioner() {
                Practitioner practitioner = new Practitioner();
                practitioner.setId(UUID.randomUUID().toString());
                practitioner.getMeta().addProfile(
                                "http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-practitioner");

                HumanName name = practitioner.addName();
                name.setFamily("Fischer");
                name.addPrefix("Mag.pharm.");
                name.addGiven("Elena");

                return practitioner;
        }

        private CommunicationRequest createCommunicationRequest(Patient patient, String description,
                        Practitioner sender, Practitioner receiver) {
                CommunicationRequest request = new CommunicationRequest();
                request.setId(UUID.randomUUID().toString());

                // Add profile for ATMessagingCommunicationRequest
                request.getMeta().addProfile(
                                "http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-communication-request");

                request.setStatus(Enumerations.RequestStatus.ACTIVE);
                request.setIntent(Enumerations.RequestIntent.ORDER);
                request.setPriority(Enumerations.RequestPriority.ROUTINE);

                // Subject - the patient
                request.setSubject(new Reference("urn:uuid:" + patient.getId())
                                .setDisplay(getPatientDisplayName(patient)));

                // Category - request for information
                request.addCategory(new CodeableConcept()
                                .addCoding(new Coding()
                                                .setSystem("http://terminology.hl7.org/CodeSystem/communication-category")
                                                .setCode("instruction")
                                                .setDisplay("Instruction")));

                // Authored on
                request.setAuthoredOn(new Date());

                // Requester (sender)
                request.setRequester(new Reference("urn:uuid:" + sender.getId())
                                .setDisplay("Dr. Johann Huber"));

                // Recipient (receiver at HIS)
                request.addRecipient(new Reference("urn:uuid:" + receiver.getId())
                                .setDisplay("Dr. Selina Mayer"));

                // Payload - the request description
                CommunicationRequest.CommunicationRequestPayloadComponent payload = request.addPayload();
                Attachment attachment = new Attachment();
                attachment.setContentType("text/plain");
                attachment.setLanguage("de");
                attachment.setData(description.getBytes(StandardCharsets.UTF_8));
                attachment.setTitle("Consult Request");
                attachment.setCreation(new Date());
                payload.setContent(attachment);

                return request;
        }

        private MessageHeader createMessageHeader(Endpoint source, Endpoint destination,
                        Practitioner sender, Practitioner receiver,
                        CommunicationRequest communicationRequest, Patient patient,
                        String destName) {
                MessageHeader messageHeader = new MessageHeader();
                messageHeader.setId(UUID.randomUUID().toString());

                // Meta profile for ATMessagingMessageHeader
                messageHeader.getMeta().addProfile(
                                "http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-message-header");

                // Event type - request (per ATMessaging event type CodeSystem)
                messageHeader.setEvent(new Coding()
                                .setSystem("http://fhir.hl7.at/fhir/ATMessaging/0.1.0/CodeSystem/at-messaging-event-type")
                                .setCode("request")
                                .setDisplay("Request"));

                // Definition - canonical URL to
                // ATMessagingCommunicationRequestMessageDefinition
                messageHeader.setDefinition(
                                "http://fhir.hl7.at/fhir/ATMessaging/0.1.0/MessageDefinition/at-messaging-communicationrequest-message");

                // Source with required ATMessagingMessageHeader fields
                MessageHeader.MessageSourceComponent sourceComponent = messageHeader.getSource();
                sourceComponent.setEndpoint(new Reference("urn:uuid:" + source.getId()));
                sourceComponent.setName(gpEndpointName);
                sourceComponent.setSoftware("at.hl7.fhir.poc.gp");
                sourceComponent.setVersion("0.1.0");
                sourceComponent.setContact(new ContactPoint()
                                .setSystem(ContactPoint.ContactPointSystem.EMAIL)
                                .setValue("dummy.gp.support@example.com"));

                // Destination with endpoint reference and receiver
                MessageHeader.MessageDestinationComponent destComponent = messageHeader.addDestination();
                destComponent.setEndpoint(new Reference("urn:uuid:" + destination.getId()));
                destComponent.setName(destName);
                destComponent.setReceiver(new Reference("urn:uuid:" + receiver.getId()));

                // Sender and author
                messageHeader.setSender(new Reference("urn:uuid:" + sender.getId()));
                messageHeader.setAuthor(new Reference("urn:uuid:" + sender.getId()));

                // Focus - the CommunicationRequest and Patient
                messageHeader.addFocus(new Reference("urn:uuid:" + communicationRequest.getId())
                                .setType("CommunicationRequest"));
                messageHeader.addFocus(new Reference("urn:uuid:" + patient.getId())
                                .setType("Patient"));

                return messageHeader;
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
