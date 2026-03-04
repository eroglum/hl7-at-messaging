const { v4: uuidv4 } = require("uuid");

// AT Messaging IG profile URLs
const PROFILES = {
  BUNDLE:
    "http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-bundle",
  MESSAGE_HEADER:
    "http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-message-header",
  ENDPOINT:
    "http://fhir.hl7.at/fhir/ATMessaging/0.1.0/StructureDefinition/at-messaging-endpoint",
  PATIENT:
    "http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-patient",
  PRACTITIONER:
    "http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-practitioner",
  ORGANIZATION:
    "http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-organization",
  PRACTITIONER_ROLE:
    "http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-practitionerRole",
};

const EVENT_TYPE_SYSTEM =
  "http://fhir.hl7.at/fhir/ATMessaging/0.1.0/CodeSystem/at-messaging-event-type";
const ENDPOINT_TYPE_SYSTEM =
  "http://fhir.hl7.at/fhir/ATMessaging/0.1.0/CodeSystem/at-messaging-endpoint-type";

const MESSAGE_DEFINITIONS = {
  communication:
    "http://fhir.hl7.at/fhir/ATMessaging/0.1.0/MessageDefinition/at-messaging-communication-message",
  document:
    "http://fhir.hl7.at/fhir/ATMessaging/0.1.0/MessageDefinition/at-messaging-document-message",
};

/**
 * Builds ATMessagingBundle conformant FHIR R5 message bundles for the Pharmacy system.
 * Follows the AT FHIR R5 Messaging IG: https://fhir.hl7.at/r5-TC-FHIR-AG-Messaging-R5-main/artifacts.html
 */
class FhirBundleBuilder {
  constructor(config = {}) {
    this.sourceEndpointName = config.sourceEndpointName || "Pharmacy System";
    this.sourceEndpointAddress =
      config.sourceEndpointAddress || "matrix:@pharmacy_user:matrix.local";
    this.softwareName = config.softwareName || "at.hl7.fhir.poc.pharmacy";
    this.softwareVersion = config.softwareVersion || "0.1.0";
    this.contactEmail = config.contactEmail || "pharmacy.support@example.com";
  }

  /**
   * Creates an ATMessagingBundle with a MedicationDispense resource.
   * Represents a pharmacy dispensing a medication for a patient.
   */
  buildMedicationDispenseBundle({
    recipientName,
    recipientAddress,
    medicationName,
    dosageInstruction,
    quantity,
    patientName,
    patientBirthDate,
  }) {
    const bundleId = uuidv4();

    // Create all resources with UUIDs
    const sourceEndpoint = this._createEndpoint(
      this.sourceEndpointName,
      this.sourceEndpointAddress,
    );
    const destEndpoint = this._createEndpoint(
      recipientName || "Recipient",
      recipientAddress || "matrix:@his_user:matrix.local",
    );

    const senderPractitioner = this._createPractitioner(
      "Mag.pharm.",
      "Elena",
      "Fischer",
    );
    const senderOrg = this._createOrganization("Apotheke am Hauptplatz");
    const senderRole = this._createPractitionerRole(
      senderPractitioner,
      senderOrg,
    );
    const receiverPractitioner = this._createPractitioner(
      "Dr.",
      recipientName?.split(" ").pop() || "Mayer",
      recipientName?.split(" ")[0] || "Selina",
    );

    const patient = this._createPatient(patientName, patientBirthDate);
    const medication = this._createMedication(medicationName);
    const medicationDispense = this._createMedicationDispense(
      medication,
      patient,
      senderPractitioner,
      dosageInstruction,
      quantity,
    );

    const messageHeader = this._createMessageHeader({
      eventCode: "communication",
      eventDisplay: "Communication",
      definition: MESSAGE_DEFINITIONS.communication,
      sourceEndpoint,
      destEndpoint,
      receiverPractitioner,
      senderRole,
      focusResources: [medicationDispense],
    });

    // Build bundle (MessageHeader must be first entry)
    const bundle = {
      resourceType: "Bundle",
      id: bundleId,
      meta: { profile: [PROFILES.BUNDLE] },
      type: "message",
      timestamp: new Date().toISOString(),
      entry: [
        this._entry(messageHeader),
        this._entry(sourceEndpoint),
        this._entry(destEndpoint),
        this._entry(receiverPractitioner),
        this._entry(senderPractitioner),
        this._entry(senderRole),
        this._entry(senderOrg),
        this._entry(patient),
        this._entry(medication),
        this._entry(medicationDispense),
      ],
    };

    console.log(
      `[FHIR] Built ATMessagingBundle (MedicationDispense) with ${bundle.entry.length} entries`,
    );
    return bundle;
  }

  // --- Resource Builders ---

  _createEndpoint(name, address) {
    return {
      resourceType: "Endpoint",
      id: uuidv4(),
      meta: { profile: [PROFILES.ENDPOINT] },
      status: "active",
      connectionType: [
        {
          coding: [
            {
              system: ENDPOINT_TYPE_SYSTEM,
              code: "matrix",
              display: "The message is transported over the Matrix protocol.",
            },
          ],
        },
      ],
      name: name,
      address: address,
    };
  }

  _createMessageHeader({
    eventCode,
    eventDisplay,
    definition,
    sourceEndpoint,
    destEndpoint,
    receiverPractitioner,
    senderRole,
    focusResources,
  }) {
    return {
      resourceType: "MessageHeader",
      id: uuidv4(),
      meta: { profile: [PROFILES.MESSAGE_HEADER] },
      eventCoding: {
        system: EVENT_TYPE_SYSTEM,
        code: eventCode,
        display: eventDisplay,
      },
      destination: [
        {
          endpointReference: { reference: `urn:uuid:${destEndpoint.id}` },
          receiver: { reference: `urn:uuid:${receiverPractitioner.id}` },
        },
      ],
      sender: { reference: `urn:uuid:${senderRole.id}` },
      author: { reference: `urn:uuid:${senderRole.id}` },
      source: {
        endpointReference: { reference: `urn:uuid:${sourceEndpoint.id}` },
        name: this.sourceEndpointName,
        software: this.softwareName,
        version: this.softwareVersion,
        contact: {
          system: "email",
          value: this.contactEmail,
        },
      },
      focus: focusResources.map((r) => ({ reference: `urn:uuid:${r.id}` })),
      definition: definition,
    };
  }

  _createPractitioner(prefix, given, family) {
    return {
      resourceType: "Practitioner",
      id: uuidv4(),
      meta: { profile: [PROFILES.PRACTITIONER] },
      name: [{ family, given: [given], prefix: [prefix] }],
    };
  }

  _createOrganization(name) {
    return {
      resourceType: "Organization",
      id: uuidv4(),
      meta: { profile: [PROFILES.ORGANIZATION] },
      type: [
        {
          coding: [
            {
              system: "http://terminology.hl7.org/CodeSystem/organization-type",
              code: "prov",
              display: "Healthcare Provider",
            },
          ],
        },
      ],
      name: name,
    };
  }

  _createPractitionerRole(practitioner, organization) {
    return {
      resourceType: "PractitionerRole",
      id: uuidv4(),
      meta: { profile: [PROFILES.PRACTITIONER_ROLE] },
      practitioner: { reference: `urn:uuid:${practitioner.id}` },
      organization: { reference: `urn:uuid:${organization.id}` },
      code: [
        {
          coding: [
            {
              system: "http://terminology.hl7.org/CodeSystem/practitioner-role",
              code: "pharmacist",
              display: "Pharmacist",
            },
          ],
        },
      ],
    };
  }

  _createPatient(name, birthDate) {
    const patient = {
      resourceType: "Patient",
      id: uuidv4(),
      meta: { profile: [PROFILES.PATIENT] },
    };

    if (name) {
      const parts = name.split(" ");
      patient.name = [
        {
          family: parts[parts.length - 1],
          given: parts.slice(0, -1),
        },
      ];
    }

    if (birthDate) {
      patient.birthDate = birthDate;
    }

    return patient;
  }

  _createMedication(medicationName) {
    return {
      resourceType: "Medication",
      id: uuidv4(),
      code: {
        coding: [
          {
            system: "http://www.whocc.no/atc",
            code: "J01CA04",
            display: medicationName || "Amoxicillin",
          },
        ],
        text: medicationName || "Amoxicillin",
      },
      status: "active",
    };
  }

  _createMedicationDispense(
    medication,
    patient,
    performer,
    dosageInstruction,
    quantity,
  ) {
    return {
      resourceType: "MedicationDispense",
      id: uuidv4(),
      status: "completed",
      medication: {
        reference: { reference: `urn:uuid:${medication.id}` },
        concept: {
          text: medication.code?.text || "Medication",
        },
      },
      subject: { reference: `urn:uuid:${patient.id}` },
      performer: [
        {
          actor: { reference: `urn:uuid:${performer.id}` },
        },
      ],
      quantity: {
        value: parseInt(quantity) || 1,
        unit: "Package(s)",
        system: "http://unitsofmeasure.org",
        code: "{Package}",
      },
      whenHandedOver: new Date().toISOString(),
      dosageInstruction: [
        {
          text: dosageInstruction || "As directed by physician",
        },
      ],
      note: [
        {
          text: `Dispensed by Apotheke am Hauptplatz. ${dosageInstruction || ""}`.trim(),
        },
      ],
    };
  }

  _entry(resource) {
    return {
      fullUrl: `urn:uuid:${resource.id}`,
      resource,
    };
  }
}

module.exports = FhirBundleBuilder;
