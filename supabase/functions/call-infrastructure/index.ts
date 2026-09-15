import { createClient } from "npm:@supabase/supabase-js@2";

type JsonRecord = Record<string, unknown>;

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const PORTAL_JWT_SECRET = Deno.env.get("PORTAL_JWT_SECRET") ?? "";
const PORTAL_JWT_ISSUER = Deno.env.get("PORTAL_JWT_ISSUER") ?? "http://localhost:5000";
const PORTAL_JWT_AUDIENCE = Deno.env.get("PORTAL_JWT_AUDIENCE") ?? "http://localhost:5000";
const PORTAL_AUTH_URL = Deno.env.get("PORTAL_AUTH_URL")
  ?? "https://vikom-demo.framenex.com/api/call-auth/validate";
const allowedOrigins = new Set(
  (Deno.env.get("PORTAL_ALLOWED_ORIGINS")
    ?? "https://vikom-demo.framenex.com,http://localhost:5173,http://127.0.0.1:5173")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean),
);

const admin = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false },
});

const allowedTypes = new Set([
  "call_offer",
  "call_answer",
  "ice_candidate",
  "call_rejected",
  "call_ended",
  "camera_state",
  "video_upgrade_request",
  "video_upgrade_response",
  "call_answered_elsewhere",
]);

function corsHeaders(req: Request): HeadersInit {
  const origin = req.headers.get("origin") ?? "";
  const allowOrigin = allowedOrigins.has(origin) || /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(origin)
    ? origin
    : [...allowedOrigins][0] ?? "http://localhost:5173";
  return {
    "access-control-allow-origin": allowOrigin,
    "access-control-allow-headers": "authorization, apikey, content-type, x-client-info",
    "access-control-allow-methods": "POST, OPTIONS",
    "vary": "Origin",
  };
}

function jsonResponse(req: Request, body: unknown, status = 200): Response {
  return Response.json(body, { status, headers: corsHeaders(req) });
}

function base64UrlBytes(value: string): Uint8Array<ArrayBuffer> {
  const normalized = value.replaceAll("-", "+").replaceAll("_", "/");
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
  const decoded = atob(padded);
  const bytes = new Uint8Array(decoded.length);
  for (let index = 0; index < decoded.length; index += 1) {
    bytes[index] = decoded.charCodeAt(index);
  }
  return bytes;
}

function decodeJwtPart(value: string): JsonRecord {
  return JSON.parse(new TextDecoder().decode(base64UrlBytes(value))) as JsonRecord;
}

async function authenticatePortalJwt(token: string): Promise<string | null> {
  if (!PORTAL_JWT_SECRET) return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  const header = decodeJwtPart(parts[0]);
  const payload = decodeJwtPart(parts[1]);
  if (header.alg !== "HS256") return null;

  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(PORTAL_JWT_SECRET),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["verify"],
  );
  const signatureValid = await crypto.subtle.verify(
    "HMAC",
    key,
    base64UrlBytes(parts[2]),
    new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
  );
  if (!signatureValid) return null;

  const now = Math.floor(Date.now() / 1000);
  if (typeof payload.exp !== "number" || payload.exp <= now) return null;
  if (payload.iss !== PORTAL_JWT_ISSUER || payload.aud !== PORTAL_JWT_AUDIENCE) return null;

  const roleClaim = payload.Role
    ?? payload["http://schemas.microsoft.com/ws/2008/06/identity/claims/role"];
  const roles = Array.isArray(roleClaim) ? roleClaim.map(String) : [String(roleClaim ?? "")];
  if (!roles.some((role) => ["Nurse", "HeadNurse", "Personnel"].includes(role))) return null;

  const id = payload["http://schemas.xmlsoap.org/ws/2005/05/identity/claims/nameidentifier"]
    ?? payload.sub;
  return typeof id === "string" && id.length >= 8 && id.length <= 128 ? id : null;
}

async function authenticatePortalViaBackend(token: string): Promise<string | null> {
  if (!PORTAL_AUTH_URL) return null;
  try {
    const response = await fetch(PORTAL_AUTH_URL, {
      method: "POST",
      headers: { authorization: `Bearer ${token}` },
    });
    if (!response.ok) return null;
    const result = await response.json() as { actorId?: unknown };
    return typeof result.actorId === "string"
        && result.actorId.length >= 8
        && result.actorId.length <= 128
      ? result.actorId
      : null;
  } catch {
    return null;
  }
}

async function authenticatedActor(req: Request): Promise<string | null> {
  const authorization = req.headers.get("authorization") ?? "";
  const token = authorization.match(/^Bearer\s+(.+)$/i)?.[1];
  if (!token) return null;

  const userClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
    global: { headers: { Authorization: `Bearer ${token}` } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data } = await userClient.auth.getUser(token);
  if (data.user?.id) return data.user.id;
  return await authenticatePortalJwt(token) ?? await authenticatePortalViaBackend(token);
}

let cachedGoogleToken: { value: string; expiresAt: number } | null = null;

function base64Url(value: Uint8Array | string): string {
  const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
  let binary = "";
  bytes.forEach((byte) => binary += String.fromCharCode(byte));
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function privateKeyBytes(pem: string): Uint8Array<ArrayBuffer> {
  const body = pem
    .replaceAll("\\n", "\n")
    .replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  const decoded = atob(body);
  const bytes = new Uint8Array(decoded.length);
  for (let index = 0; index < decoded.length; index += 1) {
    bytes[index] = decoded.charCodeAt(index);
  }
  return bytes;
}

async function googleAccessToken(): Promise<string | null> {
  if (cachedGoogleToken && cachedGoogleToken.expiresAt > Date.now() + 60_000) {
    return cachedGoogleToken.value;
  }
  // Existing ViKom projects used the FCM_* names before this function was
  // introduced. Accept both names so deploying the function does not require
  // copying private Firebase credentials into a second set of secrets.
  const clientEmail = Deno.env.get("FIREBASE_CLIENT_EMAIL") ?? Deno.env.get("FCM_CLIENT_EMAIL");
  const privateKey = Deno.env.get("FIREBASE_PRIVATE_KEY") ?? Deno.env.get("FCM_PRIVATE_KEY");
  if (!clientEmail || !privateKey) return null;

  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64Url(JSON.stringify({
    iss: clientEmail,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const signingInput = `${header}.${claims}`;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    privateKeyBytes(privateKey),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = new Uint8Array(await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(signingInput),
  ));
  const assertion = `${signingInput}.${base64Url(signature)}`;
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) throw new Error(`Google OAuth failed (${response.status})`);
  const result = await response.json() as { access_token: string; expires_in?: number };
  cachedGoogleToken = {
    value: result.access_token,
    expiresAt: Date.now() + (result.expires_in ?? 3600) * 1000,
  };
  return result.access_token;
}

function stringData(values: JsonRecord): Record<string, string> {
  return Object.fromEntries(
    Object.entries(values).map(([key, value]) => [key, typeof value === "string" ? value : JSON.stringify(value)]),
  );
}

async function sendPush(
  recipientId: string,
  type: string,
  payload: JsonRecord,
  isMissedCall = false,
): Promise<void> {
  const isMissed = type === "call_ended" && isMissedCall;
  if (type !== "call_offer" && !isMissed) return;
  const projectId = Deno.env.get("FIREBASE_PROJECT_ID") ?? Deno.env.get("FCM_PROJECT_ID");
  if (!projectId) return;
  const { data: profile } = await admin
    .from("profiles")
    .select("fcm_token")
    .eq("id", recipientId)
    .maybeSingle();
  if (!profile?.fcm_token) return;
  const accessToken = await googleAccessToken();
  if (!accessToken) return;
  let notificationPayload = payload;
  if (isMissed && (!payload.callerName || !payload.mediaType)) {
    const callId = String(payload.callId ?? "");
    const { data: offer } = await admin
      .from("call_signaling_events")
      .select("payload")
      .eq("call_id", callId)
      .eq("event_type", "call_offer")
      .order("id", { ascending: true })
      .limit(1)
      .maybeSingle();
    notificationPayload = { ...(offer?.payload ?? {}), ...payload };
  }
  const data = isMissed
    ? stringData({
      type: "missed_call",
      target_user_id: recipientId,
      call_id: notificationPayload.callId ?? "",
      caller_name: notificationPayload.callerName ?? "",
      media_type: notificationPayload.mediaType ?? "audio",
    })
    : stringData({
      type: "incoming_call",
      target_user_id: recipientId,
      call_id: payload.callId ?? "",
    });
  const response = await fetch(
    `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`,
    {
      method: "POST",
      headers: {
        authorization: `Bearer ${accessToken}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        message: {
          token: profile.fcm_token,
          data,
          android: { priority: "high", ttl: isMissed ? "604800s" : "60s" },
        },
      }),
    },
  );
  if (!response.ok) throw new Error(`FCM send failed (${response.status})`);
}

async function turnCredentials(): Promise<unknown> {
  const keyId = Deno.env.get("CLOUDFLARE_TURN_KEY_ID");
  const apiToken = Deno.env.get("CLOUDFLARE_TURN_API_TOKEN");
  if (!keyId || !apiToken) return { iceServers: [] };
  const response = await fetch(
    `https://rtc.live.cloudflare.com/v1/turn/keys/${encodeURIComponent(keyId)}/credentials/generate-ice-servers`,
    {
      method: "POST",
      headers: { authorization: `Bearer ${apiToken}`, "content-type": "application/json" },
      body: JSON.stringify({ ttl: 86_400 }),
    },
  );
  if (!response.ok) throw new Error(`TURN credential generation failed (${response.status})`);
  return await response.json();
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders(req) });
  if (req.method !== "POST") return jsonResponse(req, { error: "Method not allowed" }, 405);
  try {
    const actorId = await authenticatedActor(req);
    if (!actorId) return jsonResponse(req, { error: "Unauthorized" }, 401);
    const body = await req.json() as JsonRecord;
    const action = String(body.action ?? "");

    if (action === "turn") return jsonResponse(req, await turnCredentials());

    if (action === "poll") {
      const { data, error } = await admin
        .from("call_signaling_events")
        .select("id,call_id,sender_id,event_type,payload,created_at")
        .eq("recipient_id", actorId)
        .is("consumed_at", null)
        .gt("expires_at", new Date().toISOString())
        .order("id", { ascending: true })
        .limit(200);
      if (error) throw error;
      return jsonResponse(req, { events: data ?? [] });
    }

    if (action === "ack") {
      const ids = Array.isArray(body.ids)
        ? body.ids.map(Number).filter((id) => Number.isSafeInteger(id) && id > 0).slice(0, 200)
        : [];
      if (ids.length > 0) {
        const { error } = await admin
          .from("call_signaling_events")
          .update({ consumed_at: new Date().toISOString() })
          .eq("recipient_id", actorId)
          .in("id", ids);
        if (error) throw error;
      }
      return jsonResponse(req, { acknowledged: ids.length });
    }

    if (action === "send") {
      const recipientId = String(body.targetUserId ?? "").trim();
      const eventType = String(body.type ?? "").trim();
      const payload = body.payload && typeof body.payload === "object" && !Array.isArray(body.payload)
        ? body.payload as JsonRecord
        : {};
      const callId = String(payload.callId ?? body.callId ?? "").trim();
      if (recipientId.length < 8 || recipientId.length > 128 || recipientId === actorId) {
        return jsonResponse(req, { error: "Invalid recipient" }, 400);
      }
      if (!allowedTypes.has(eventType) || callId.length < 8 || callId.length > 160) {
        return jsonResponse(req, { error: "Invalid call event" }, 400);
      }
      if (eventType === "call_offer") {
        // One inexpensive cleanup per call keeps expired ICE rows from growing
        // indefinitely without requiring pg_cron on the Supabase project.
        await admin
          .from("call_signaling_events")
          .delete()
          .lt("expires_at", new Date().toISOString());
      }
      let isMissedCall = false;
      if (eventType === "call_ended") {
        // A caller may hang up before the 60-second timer. That is still a
        // missed call when no answer was ever stored for this call ID, whereas
        // hanging up an established call must not create a missed-call alert.
        const { count, error: answerLookupError } = await admin
          .from("call_signaling_events")
          .select("id", { count: "exact", head: true })
          .eq("call_id", callId)
          .eq("event_type", "call_answer");
        if (answerLookupError) throw answerLookupError;
        isMissedCall = (count ?? 0) === 0;
      }
      const expiryDays = eventType === "ice_candidate" ? 1 / 24 : 7;
      const { data, error } = await admin
        .from("call_signaling_events")
        .insert({
          call_id: callId,
          sender_id: actorId,
          recipient_id: recipientId,
          event_type: eventType,
          payload,
          expires_at: new Date(Date.now() + expiryDays * 86_400_000).toISOString(),
        })
        .select("id")
        .single();
      if (error) throw error;
      // Push is a wake-up hint; authoritative SDP remains in Postgres and is
      // fetched over HTTPS, avoiding FCM's small payload limit.
      await sendPush(recipientId, eventType, payload, isMissedCall).catch((error) =>
        console.error("Push delivery failed", error instanceof Error ? error.message : error)
      );
      return jsonResponse(req, { id: data.id }, 201);
    }

    return jsonResponse(req, { error: "Unknown action" }, 400);
  } catch (error) {
    console.error(error);
    return jsonResponse(req, { error: "Call infrastructure request failed" }, 500);
  }
});
