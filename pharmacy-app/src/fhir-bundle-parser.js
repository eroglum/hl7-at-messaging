/**
 * Parses received ATMessagingBundle and extracts resources.
 * Handles document, communication, communicationrequest, and servicerequest event types.
 */
class FhirBundleParser {
  constructor() {
    // In-memory storage for received messages
    this.messages = [];
  }

  parseAndSave(bundleJson) {
    try {
      const bundle = typeof bundleJson === 'string' ? JSON.parse(bundleJson) : bundleJson;

      if (!bundle || bundle.resourceType !== 'Bundle' || bundle.type !== 'message') {
        console.log('[FHIR Parser] Not a message bundle, skipping');
        return null;
      }

      const entries = bundle.entry || [];
      if (entries.length === 0) return null;

      // First entry must be MessageHeader
      const messageHeader = entries[0]?.resource;
      if (!messageHeader || messageHeader.resourceType !== 'MessageHeader') {
        console.log('[FHIR Parser] First entry is not MessageHeader, skipping');
        return null;
      }

      // Extract event type
      const eventCode = this._getEventCode(messageHeader);
      const sourceName = this._getSourceName(messageHeader);

      // Build a parsed message object
      const message = {
        id: bundle.id || `msg-${Date.now()}`,
        bundleId: bundle.id,
        timestamp: bundle.timestamp || new Date().toISOString(),
        eventType: eventCode,
        sourceName: sourceName,
        sourceEndpoint: null,
        patient: null,
        documents: [],
        communications: [],
        communicationRequests: [],
        rawBundle: bundle,
      };

      // Extract all resources by type
      const resources = {};
      for (const entry of entries) {
        const r = entry.resource;
        if (!r) continue;
        if (!resources[r.resourceType]) resources[r.resourceType] = [];
        resources[r.resourceType].push(r);
      }

      // Extract patient info
      if (resources.Patient && resources.Patient.length > 0) {
        const patient = resources.Patient[0];
        message.patient = {
          name: this._getPatientDisplayName(patient),
          birthDate: patient.birthDate || null,
          gender: patient.gender || null,
          id: patient.id,
        };
      }

      // Extract source endpoint
      if (resources.Endpoint) {
        // Source endpoint is referenced by messageHeader.source.endpointReference
        const sourceRef = messageHeader.source?.endpointReference?.reference;
        if (sourceRef) {
          const ep = resources.Endpoint.find((e) => `urn:uuid:${e.id}` === sourceRef);
          if (ep) {
            message.sourceEndpoint = { name: ep.name, address: ep.address };
          }
        }
      }

      // Extract DocumentReferences
      if (resources.DocumentReference) {
        for (const doc of resources.DocumentReference) {
          const docInfo = {
            id: doc.id,
            title: doc.description || this._getDocType(doc) || 'Document',
            type: this._getDocType(doc),
            category: this._getDocCategory(doc),
            date: doc.date || null,
            hasPdf: false,
            pdfBase64: null,
          };

          // Check for PDF content
          if (doc.content) {
            for (const c of doc.content) {
              if (c.attachment?.contentType === 'application/pdf' && c.attachment?.data) {
                docInfo.hasPdf = true;
                docInfo.pdfBase64 = c.attachment.data;
              }
            }
          }

          message.documents.push(docInfo);
        }
      }

      // Extract Communications
      if (resources.Communication) {
        for (const comm of resources.Communication) {
          const commInfo = {
            id: comm.id,
            status: comm.status,
            text: null,
          };

          if (comm.payload) {
            for (const p of comm.payload) {
              if (p.contentAttachment?.data) {
                try {
                  commInfo.text = Buffer.from(p.contentAttachment.data, 'base64').toString('utf-8');
                } catch { /* ignore */ }
              }
              if (p.contentString) {
                commInfo.text = p.contentString;
              }
            }
          }

          message.communications.push(commInfo);
        }
      }

      // Extract CommunicationRequests
      if (resources.CommunicationRequest) {
        for (const cr of resources.CommunicationRequest) {
          const crInfo = {
            id: cr.id,
            status: cr.status,
            text: null,
          };

          if (cr.payload) {
            for (const p of cr.payload) {
              if (p.contentAttachment?.data) {
                try {
                  crInfo.text = Buffer.from(p.contentAttachment.data, 'base64').toString('utf-8');
                } catch { /* ignore */ }
              }
              if (p.contentString) {
                crInfo.text = p.contentString;
              }
            }
          }

          message.communicationRequests.push(crInfo);
        }
      }

      // Check for duplicate
      if (this.messages.findIndex((m) => m.bundleId === message.bundleId) >= 0) {
        console.log(`[FHIR Parser] Duplicate bundle ${message.bundleId}, skipping`);
        return null;
      }

      this.messages.unshift(message); // newest first
      console.log(`[FHIR Parser] Parsed ${eventCode} message from ${sourceName}, ${message.documents.length} docs, ${message.communications.length} comms`);
      return message;
    } catch (err) {
      console.error('[FHIR Parser] Error parsing bundle:', err.message);
      return null;
    }
  }

  getMessages() {
    return this.messages;
  }

  getMessage(id) {
    return this.messages.find((m) => m.id === id || m.bundleId === id);
  }

  getMessageCount() {
    return this.messages.length;
  }

  // --- Helpers ---

  _getEventCode(messageHeader) {
    return messageHeader.eventCoding?.code || messageHeader.event?.code || 'unknown';
  }

  _getSourceName(messageHeader) {
    return messageHeader.source?.name || 'Unknown Source';
  }

  _getPatientDisplayName(patient) {
    if (!patient.name || patient.name.length === 0) return 'Unknown Patient';
    const n = patient.name[0];
    const parts = [];
    if (n.prefix) parts.push(...n.prefix);
    if (n.given) parts.push(...n.given);
    if (n.family) parts.push(n.family);
    return parts.join(' ') || 'Unknown Patient';
  }

  _getDocType(doc) {
    return doc.type?.coding?.[0]?.display || doc.type?.text || null;
  }

  _getDocCategory(doc) {
    return doc.category?.[0]?.coding?.[0]?.display || null;
  }
}

module.exports = FhirBundleParser;
