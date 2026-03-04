package at.hl7.fhir.poc.gp.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r5.model.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides the 3 dummy patients for the GP application.
 * These mirror the patients in HIS for consistent patient selection.
 */
@Service
@Slf4j
public class PatientService {

    private final List<Patient> patients = new ArrayList<>();

    @PostConstruct
    public void init() {
        log.info("Initializing dummy patient data for GP app...");

        // Same 3 patients as HIS - using fixed IDs for consistency
        patients.add(createPatient("pat-001", "Max", "Mustermann", "1985-03-15", "male", "1234567890"));
        patients.add(createPatient("pat-002", "Maria", "Musterfrau", "1990-07-22", "female", "0987654321"));
        patients.add(createPatient("pat-003", "Johann", "Schmidt", "1978-11-08", "male", "5555555555"));
        patients.add(createPatient("pat-004", "Muhammed", "Eroglu", "2001-02-18", "male", "1111111111"));

        log.info("Initialized {} dummy patients", patients.size());
    }

    private Patient createPatient(String id, String firstName, String lastName, String birthDate,
            String gender, String socialSecurityNumber) {
        Patient patient = new Patient();
        patient.setId(id);
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

        return patient;
    }

    public List<Patient> getAllPatients() {
        return new ArrayList<>(patients);
    }

    public Patient getPatient(String id) {
        return patients.stream()
                .filter(p -> p.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * Gets the display name for a patient.
     */
    public String getPatientDisplayName(Patient patient) {
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
