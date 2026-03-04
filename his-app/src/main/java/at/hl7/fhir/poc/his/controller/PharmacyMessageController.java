package at.hl7.fhir.poc.his.controller;

import at.hl7.fhir.poc.his.service.CommunicationBundleBuilder;
import at.hl7.fhir.poc.his.service.FhirService;
import at.hl7.fhir.poc.his.service.MatrixService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r5.model.Bundle;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for sending Communication messages from HIS to Pharmacy.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class PharmacyMessageController {

    private final CommunicationBundleBuilder communicationBundleBuilder;
    private final FhirService fhirService;
    private final MatrixService matrixService;

    @GetMapping("/pharmacy/send")
    public String showSendForm(Model model) {
        model.addAttribute("matrixConnected", matrixService.isConnected());
        return "send-pharmacy-message";
    }

    @PostMapping("/pharmacy/send")
    public String sendMessage(@RequestParam String patientName,
            @RequestParam(required = false) String patientBirthDate,
            @RequestParam String messageText,
            RedirectAttributes redirectAttributes) {

        if (messageText == null || messageText.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Message text is required");
            return "redirect:/pharmacy/send";
        }

        try {
            Bundle bundle = communicationBundleBuilder.createPharmacyCommunicationBundle(
                    patientName, patientBirthDate, messageText);

            // Save bundle locally
            fhirService.saveBundle(bundle);

            // Serialize and send via Matrix
            String bundleJson = fhirService.serializeResource(bundle);
            boolean sent = matrixService.sendFhirMessage(bundleJson);

            if (sent) {
                log.info("Successfully sent communication to Pharmacy for patient: {}", patientName);
                redirectAttributes.addFlashAttribute("success",
                        "Message sent successfully to Pharmacy for " + patientName);
            } else {
                log.error("Failed to send communication to Pharmacy via Matrix");
                redirectAttributes.addFlashAttribute("error",
                        "Failed to send message. Matrix connection issue.");
            }

        } catch (Exception e) {
            log.error("Error sending message to Pharmacy", e);
            redirectAttributes.addFlashAttribute("error",
                    "Error sending message: " + e.getMessage());
        }

        return "redirect:/";
    }
}
