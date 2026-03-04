package at.hl7.fhir.poc.gp.controller;

import at.hl7.fhir.poc.gp.model.SentRequest;
import at.hl7.fhir.poc.gp.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Patient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Date;

/**
 * Controller for handling consult request form and sending CommunicationRequest
 * messages to HIS.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ConsultRequestController {

    private final PatientService patientService;
    private final CommunicationRequestBundleBuilder bundleBuilder;
    private final FhirService fhirService;
    private final MatrixService matrixService;
    private final SentRequestService sentRequestService;

    @GetMapping("/consult-request")
    public String showRequestForm(@RequestParam(required = false) String patientId, Model model) {
        model.addAttribute("patients", patientService.getAllPatients());
        model.addAttribute("matrixConnected", matrixService.isConnected());

        if (patientId != null) {
            Patient patient = patientService.getPatient(patientId);
            if (patient != null) {
                model.addAttribute("selectedPatient", patient);
                model.addAttribute("selectedPatientId", patientId);
            }
        }

        return "consult-request";
    }

    @PostMapping("/consult-request/send")
    public String sendRequest(@RequestParam String patientId,
            @RequestParam String description,
            @RequestParam(defaultValue = "his") String recipient,
            RedirectAttributes redirectAttributes) {

        // Validate input
        if (patientId == null || patientId.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Please select a patient");
            return "redirect:/consult-request";
        }

        if (description == null || description.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Please enter a request description");
            return "redirect:/consult-request?patientId=" + patientId;
        }

        // Get patient
        Patient patient = patientService.getPatient(patientId);
        if (patient == null) {
            redirectAttributes.addFlashAttribute("error", "Patient not found");
            return "redirect:/consult-request";
        }

        try {
            // Build the CommunicationRequest bundle
            Bundle bundle = bundleBuilder.createCommunicationRequestBundle(patient, description, recipient);

            // Capture the original bundle ID before saving
            String originalBundleId = bundle.getId();

            // Save bundle locally
            Bundle savedBundle = fhirService.saveBundle(bundle);

            // Serialize and send via Matrix
            String bundleJson = fhirService.serializeResource(bundle);
            boolean sent = matrixService.sendFhirMessage(bundleJson);

            if (sent) {
                String patientName = patientService.getPatientDisplayName(patient);
                String recipientLabel = "pharmacy".equals(recipient) ? "Pharmacy" : "HIS";
                log.info("Successfully sent consult request to {} for patient: {}", recipientLabel, patientName);
                redirectAttributes.addFlashAttribute("success",
                        "Consult request sent successfully to " + recipientLabel + " for " + patientName);

                // Track the sent request
                SentRequest sentRequest = new SentRequest();
                sentRequest.setId(java.util.UUID.randomUUID().toString());
                sentRequest.setSentAt(new Date());
                sentRequest.setBundleId(originalBundleId);
                sentRequest.setFhirBundleId(savedBundle != null ? savedBundle.getIdElement().getIdPart() : null);
                sentRequest.setBundleJson(bundleJson);
                sentRequest.setPatientName(patientName);
                sentRequest.setPatientBirthDate(
                        patient.hasBirthDate() ? patient.getBirthDateElement().getValueAsString() : null);
                sentRequest.setPatientId(patientId);
                sentRequest.setDescription(description);
                sentRequest.setStatus("sent");
                sentRequest.setRecipientName("Dr. Selina Mayer");
                sentRequestService.addSentRequest(sentRequest);
            } else {
                log.error("Failed to send consult request via Matrix");
                redirectAttributes.addFlashAttribute("error",
                        "Failed to send request. Matrix connection issue.");
            }

        } catch (Exception e) {
            log.error("Error sending consult request", e);
            redirectAttributes.addFlashAttribute("error",
                    "Error sending request: " + e.getMessage());
        }

        return "redirect:/";
    }
}
