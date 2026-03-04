const axios = require('axios');
const { v4: uuidv4 } = require('uuid');

class MatrixClient {
  constructor({ serverUrl, username, password, roomAlias = 'messaging', serverName = 'matrix.local' }) {
    this.serverUrl = serverUrl;
    this.username = username;
    this.password = password;
    this.roomAlias = roomAlias;
    this.serverName = serverName;
    this.accessToken = null;
    this.roomId = null;
    this.nextBatch = null;
    this.onMessage = null; // callback for incoming FHIR messages
    this.pollInterval = null;

    // Create axios instance with large buffer for FHIR bundles with PDFs
    this.client = axios.create({
      baseURL: serverUrl,
      maxContentLength: 50 * 1024 * 1024,
      maxBodyLength: 50 * 1024 * 1024,
    });
  }

  async login() {
    try {
      const res = await this.client.post('/_matrix/client/v3/login', {
        type: 'm.login.password',
        user: this.username,
        password: this.password,
      });
      this.accessToken = res.data.access_token;
      console.log(`[Matrix] Logged in as ${this.username}`);

      await this.resolveRoomId();
      await this.joinRoom();
      await this.initialSync();

      return true;
    } catch (err) {
      console.error('[Matrix] Login failed:', err.message);
      return false;
    }
  }

  async resolveRoomId() {
    try {
      const alias = encodeURIComponent(`#${this.roomAlias}:${this.serverName}`);
      const res = await this.client.get(`/_matrix/client/v3/directory/room/${alias}`, {
        headers: { Authorization: `Bearer ${this.accessToken}` },
      });
      this.roomId = res.data.room_id;
      console.log(`[Matrix] Resolved room alias '${this.roomAlias}' to ${this.roomId}`);
    } catch (err) {
      console.error('[Matrix] Failed to resolve room alias:', err.message);
    }
  }

  async joinRoom() {
    if (!this.roomId) return;
    try {
      await this.client.post(
        `/_matrix/client/v3/join/${encodeURIComponent(this.roomId)}`,
        {},
        { headers: { Authorization: `Bearer ${this.accessToken}` } }
      );
      console.log(`[Matrix] Joined room ${this.roomId}`);
    } catch (err) {
      console.warn('[Matrix] Join room (may already be joined):', err.message);
    }
  }

  async initialSync() {
    try {
      const res = await this.client.get('/_matrix/client/v3/sync', {
        params: { timeout: 0 },
        headers: { Authorization: `Bearer ${this.accessToken}` },
      });
      this.nextBatch = res.data.next_batch;
      console.log(`[Matrix] Initial sync complete, next_batch: ${this.nextBatch}`);
    } catch (err) {
      console.error('[Matrix] Initial sync failed:', err.message);
    }
  }

  startPolling(intervalMs = 5000) {
    this.pollInterval = setInterval(() => this.poll(), intervalMs);
    console.log(`[Matrix] Polling started (every ${intervalMs}ms)`);
  }

  stopPolling() {
    if (this.pollInterval) {
      clearInterval(this.pollInterval);
      this.pollInterval = null;
    }
  }

  async poll() {
    if (!this.accessToken || !this.roomId || !this.nextBatch) return;

    try {
      const res = await this.client.get('/_matrix/client/v3/sync', {
        params: { since: this.nextBatch, timeout: 3000 },
        headers: { Authorization: `Bearer ${this.accessToken}` },
      });

      this.nextBatch = res.data.next_batch;

      const roomData = res.data?.rooms?.join?.[this.roomId];
      const events = roomData?.timeline?.events || [];

      for (const event of events) {
        await this.processEvent(event);
      }
    } catch (err) {
      // Silently ignore poll errors (connection timeouts etc.)
    }
  }

  async processEvent(event) {
    try {
      if (event.type !== 'm.room.message') return;

      const content = event.content;
      if (content.msgtype !== 'at.fhir.message') return;

      let fhirBundle = null;

      // Check for media URL first (large bundles with PDFs)
      const mxcUri = content.fhir_bundle_url;
      if (mxcUri && mxcUri.startsWith('mxc://')) {
        console.log(`[Matrix] Downloading FHIR bundle from media: ${mxcUri}`);
        fhirBundle = await this.downloadMedia(mxcUri);
      }

      // Fall back to inline bundle
      if (!fhirBundle) {
        fhirBundle = content.fhir_bundle;
      }

      if (fhirBundle && this.onMessage) {
        console.log('[Matrix] Received FHIR message');
        this.onMessage(fhirBundle);
      }
    } catch (err) {
      console.error('[Matrix] Error processing event:', err.message);
    }
  }

  async downloadMedia(mxcUri) {
    try {
      const uriPart = mxcUri.substring(6); // Remove "mxc://"
      const [serverName, mediaId] = uriPart.split('/', 2);

      const res = await this.client.get(
        `/_matrix/client/v1/media/download/${serverName}/${mediaId}`,
        {
          headers: { Authorization: `Bearer ${this.accessToken}` },
          responseType: 'text',
        }
      );
      console.log(`[Matrix] Downloaded media: ${typeof res.data === 'string' ? res.data.length : '?'} chars`);
      return typeof res.data === 'string' ? res.data : JSON.stringify(res.data);
    } catch (err) {
      console.error('[Matrix] Failed to download media:', err.message);
      return null;
    }
  }

  async sendFhirMessage(fhirBundleJson) {
    if (!this.accessToken) {
      const ok = await this.login();
      if (!ok) return false;
    }

    if (!this.roomId) {
      console.error('[Matrix] Room ID not resolved');
      return false;
    }

    try {
      // Upload the FHIR bundle as media
      const mxcUri = await this.uploadMedia(
        Buffer.from(fhirBundleJson, 'utf-8'),
        'application/json',
        'fhir-bundle.json'
      );
      if (!mxcUri) {
        console.error('[Matrix] Failed to upload FHIR bundle to media');
        return false;
      }

      const txnId = uuidv4();

      // Send message event referencing uploaded media
      await this.client.put(
        `/_matrix/client/v3/rooms/${encodeURIComponent(this.roomId)}/send/m.room.message/${txnId}`,
        {
          msgtype: 'at.fhir.message',
          body: 'FHIR Message Bundle',
          fhir_bundle_url: mxcUri,
        },
        { headers: { Authorization: `Bearer ${this.accessToken}` } }
      );

      console.log(`[Matrix] Sent FHIR message via media upload, mxc: ${mxcUri}`);
      return true;
    } catch (err) {
      console.error('[Matrix] Failed to send FHIR message:', err.message);
      return false;
    }
  }

  async uploadMedia(content, contentType, filename) {
    try {
      const res = await this.client.post('/_matrix/media/v3/upload', content, {
        params: { filename },
        headers: {
          Authorization: `Bearer ${this.accessToken}`,
          'Content-Type': contentType,
        },
      });
      const mxcUri = res.data.content_uri;
      console.log(`[Matrix] Uploaded media: ${mxcUri} (${content.length} bytes)`);
      return mxcUri;
    } catch (err) {
      console.error('[Matrix] Failed to upload media:', err.message);
      return null;
    }
  }

  isConnected() {
    return this.accessToken !== null && this.roomId !== null;
  }
}

module.exports = MatrixClient;
