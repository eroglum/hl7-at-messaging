package at.hl7.fhir.poc.his.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r5.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class FhirService {

    @Value("${fhir.server.url}")
    private String fhirServerUrl;

    private FhirContext fhirContext;
    private IGenericClient fhirClient;

    @PostConstruct
    public void init() {
        fhirContext = FhirContext.forR5();
        fhirContext.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        fhirContext.getRestfulClientFactory().setConnectTimeout(60000);
        fhirContext.getRestfulClientFactory().setSocketTimeout(60000);
        fhirClient = fhirContext.newRestfulGenericClient(fhirServerUrl);

        log.info("FHIR client initialized for server: {}", fhirServerUrl);
    }

    public List<Patient> getAllPatients() {
        try {
            Bundle bundle = fhirClient.search()
                    .forResource(Patient.class)
                    .returnBundle(Bundle.class)
                    .execute();

            List<Patient> patients = new ArrayList<>();
            for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                if (entry.getResource() instanceof Patient) {
                    patients.add((Patient) entry.getResource());
                }
            }
            return patients;
        } catch (Exception e) {
            log.error("Error fetching patients", e);
            return new ArrayList<>();
        }
    }

    public Patient getPatient(String id) {
        try {
            return fhirClient.read()
                    .resource(Patient.class)
                    .withId(id)
                    .execute();
        } catch (Exception e) {
            log.error("Error fetching patient {}", id, e);
            return null;
        }
    }

    public Patient createPatient(Patient patient) {
        try {
            var outcome = fhirClient.create()
                    .resource(patient)
                    .execute();
            return (Patient) outcome.getResource();
        } catch (Exception e) {
            log.error("Error creating patient", e);
            return null;
        }
    }

    public Bundle saveBundle(Bundle bundle) {
        try {
            var outcome = fhirClient.create()
                    .resource(bundle)
                    .execute();
            return (Bundle) outcome.getResource();
        } catch (Exception e) {
            log.error("Error saving bundle", e);
            return null;
        }
    }

    public List<Bundle> getMessageBundles() {
        try {
            Bundle searchResult = fhirClient.search()
                    .forResource(Bundle.class)
                    .where(Bundle.TYPE.exactly().code("message"))
                    .returnBundle(Bundle.class)
                    .execute();

            List<Bundle> bundles = new ArrayList<>();
            for (Bundle.BundleEntryComponent entry : searchResult.getEntry()) {
                if (entry.getResource() instanceof Bundle) {
                    bundles.add((Bundle) entry.getResource());
                }
            }
            return bundles;
        } catch (Exception e) {
            log.error("Error fetching message bundles", e);
            return new ArrayList<>();
        }
    }

    public String serializeResource(Resource resource) {
        return fhirContext.newJsonParser().setPrettyPrint(false).encodeResourceToString(resource);
    }

    public Resource parseResource(String json) {
        return (Resource) fhirContext.newJsonParser().parseResource(json);
    }

    public void initializeSampleData() {
        try {
            // Check if patients already exist
            List<Patient> existingPatients = getAllPatients();
            if (!existingPatients.isEmpty()) {
                log.info("Sample data already exists, skipping initialization");
                return;
            }

            log.info("Initializing sample patient data...");

            // Create sample patients
            createSamplePatient("Max", "Mustermann", "1985-03-15", "male", "1234567890");
            createSamplePatient("Maria", "Musterfrau", "1990-07-22", "female", "0987654321");
            createSamplePatient("Johann", "Schmidt", "1978-11-08", "male", "5555555555");
            createSamplePatient("Muhammed", "Eroglu", "2001-02-18", "male", "1111111111");

            log.info("Sample data initialization complete");
        } catch (Exception e) {
            log.error("Error initializing sample data", e);
        }
    }

    private void createSamplePatient(String firstName, String lastName, String birthDate,
            String gender, String socialSecurityNumber) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID().toString());
        patient.getMeta().addProfile("http://hl7.at/fhir/HL7ATCoreProfiles/5.0.0/StructureDefinition/at-core-patient");

        HumanName name = patient.addName();
        name.setFamily(lastName);
        name.addGiven(firstName);
        name.setUse(HumanName.NameUse.OFFICIAL);

        patient.setBirthDateElement(new DateType(birthDate));
        patient.setGender(Enumerations.AdministrativeGender.fromCode(gender));

        // Add Austrian social security number as identifier
        Identifier ssn = patient.addIdentifier();
        ssn.setSystem("urn:oid:1.2.40.0.10.1.4.3.1");
        ssn.setValue(socialSecurityNumber);
        ssn.setUse(Identifier.IdentifierUse.OFFICIAL);

        createPatient(patient);
        log.info("Created patient: {} {}", firstName, lastName);
    }
}
