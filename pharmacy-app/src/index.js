const express = require('express');
const path = require('path');
const multer = require('multer');
const MatrixClient = require('./matrix-client');
const FhirBundleBuilder = require('./fhir-bundle-builder');
const FhirBundleParser = require('./fhir-bundle-parser');

const app = express();
const upload = multer();
const PORT = process.env.PORT || 8080;

// Configuration from environment
const config = {
  matrixServerUrl: process.env.MATRIX_SERVER_URL || 'http://matrix:8008',
  matrixUsername: process.env.MATRIX_USERNAME || 'pharmacy_user',
  matrixPassword: process.env.MATRIX_PASSWORD || 'pharmacy_password',
  matrixRoomAlias: process.env.MATRIX_ROOM_ALIAS || 'messaging',
  matrixServerName: process.env.MATRIX_SERVER_NAME || 'matrix.local',
};

// Initialize services
const matrixClient = new MatrixClient({
  serverUrl: config.matrixServerUrl,
  username: config.matrixUsername,
  password: config.matrixPassword,
  roomAlias: config.matrixRoomAlias,
  serverName: config.matrixServerName,
});

const bundleBuilder = new FhirBundleBuilder({
  sourceEndpointName: 'Pharmacy System',
  sourceEndpointAddress: `matrix:@${config.matrixUsername}:${config.matrixServerName}`,
});

const bundleParser = new FhirBundleParser();

// Register message handler
matrixClient.onMessage = (fhirBundleJson) => {
  bundleParser.parseAndSave(fhirBundleJson);
};

// Express setup
app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'views'));
app.use(express.static(path.join(__dirname, 'public')));
app.use(express.urlencoded({ extended: true }));
app.use(express.json());

// --- Routes ---

// Dashboard / Inbox
app.get('/', (req, res) => {
  res.render('index', {
    messages: bundleParser.getMessages(),
    messageCount: bundleParser.getMessageCount(),
    connected: matrixClient.isConnected(),
  });
});

// Message detail
app.get('/message/:id', (req, res) => {
  const message = bundleParser.getMessage(req.params.id);
  if (!message) {
    return res.status(404).render('error', { error: 'Message not found' });
  }
  res.render('message', { message });
});

// PDF download for document
app.get('/message/:id/pdf/:docId', (req, res) => {
  const message = bundleParser.getMessage(req.params.id);
  if (!message) return res.status(404).send('Message not found');

  const doc = message.documents.find((d) => d.id === req.params.docId);
  if (!doc || !doc.pdfBase64) return res.status(404).send('PDF not found');

  const pdfBuffer = Buffer.from(doc.pdfBase64, 'base64');
  res.contentType('application/pdf');
  res.setHeader('Content-Disposition', `inline; filename="${doc.title || 'document'}.pdf"`);
  res.send(pdfBuffer);
});

// Send medication dispense notification
app.post('/send', upload.none(), async (req, res) => {
  try {
    const { recipientAddress, medicationName, dosageInstruction, quantity, patientName, patientBirthDate } = req.body;

    if (!medicationName || medicationName.trim() === '') {
      return res.redirect('/?error=Medication name is required');
    }

    // Determine recipient name from address
    let recipientName = 'Hospital Information System';
    if (recipientAddress && recipientAddress.includes('gp_user')) {
      recipientName = 'General Practitioner - Dr. Huber';
    }

    const bundle = bundleBuilder.buildMedicationDispenseBundle({
      recipientName,
      recipientAddress: recipientAddress || 'matrix:@his_user:matrix.local',
      medicationName,
      dosageInstruction: dosageInstruction || '',
      quantity: quantity || '1',
      patientName: patientName || '',
      patientBirthDate: patientBirthDate || '',
    });

    const bundleJson = JSON.stringify(bundle, null, 2);
    const success = await matrixClient.sendFhirMessage(bundleJson);

    if (success) {
      console.log('[App] MedicationDispense notification sent successfully');
      res.redirect('/?success=Medication dispense notification sent successfully');
    } else {
      res.redirect('/?error=Failed to send notification');
    }
  } catch (err) {
    console.error('[App] Error sending medication dispense:', err.message);
    res.redirect('/?error=' + encodeURIComponent(err.message));
  }
});

// API: message count (for polling UI updates)
app.get('/api/message-count', (req, res) => {
  res.json({ count: bundleParser.getMessageCount() });
});

// Health check
app.get('/actuator/health', (req, res) => {
  res.json({ status: 'UP', matrixConnected: matrixClient.isConnected() });
});

// --- Startup ---
async function start() {
  // Connect to Matrix
  console.log(`[App] Connecting to Matrix at ${config.matrixServerUrl}...`);
  let connected = false;
  for (let attempt = 1; attempt <= 30; attempt++) {
    connected = await matrixClient.login();
    if (connected) break;
    console.log(`[App] Matrix not ready, retrying in 5s (attempt ${attempt}/30)...`);
    await new Promise((r) => setTimeout(r, 5000));
  }

  if (!connected) {
    console.error('[App] Could not connect to Matrix after 30 attempts');
  } else {
    matrixClient.startPolling(5000);
  }

  // Start Express
  app.listen(PORT, '0.0.0.0', () => {
    console.log(`[App] Pharmacy app running on http://0.0.0.0:${PORT}`);
  });
}

start().catch((err) => {
  console.error('[App] Fatal error:', err);
  process.exit(1);
});
