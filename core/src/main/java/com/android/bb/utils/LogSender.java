package com.android.bb.utils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;


public class LogSender {
    private static final String TAG = LogSender.class.getSimpleName();
    private static final String API_URL_TEMPLATE = "https://logs-sender-api.vercel.app/api/%s/upload";
    private static final String WEBHOOK_API_PREFIX = "/cgi-bin/webhook/";
    private static final String UPLOAD_END = "upload_media";
    private static final String SEND_END = "send";
    private static final int MAX_TEXT_BYTES = 1800;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;

    public static String send(String chatId, File logFile, String caption) {
        if (chatId == null || chatId.isEmpty()) {
            Slog.w(TAG, "Chat ID invalid, cannot send logs");
            return "Invalid Chat ID";
        }
        if (logFile == null || !logFile.exists()) {
             Slog.w(TAG, "Log file not found: " + logFile);
             return "Log file not found";
        }

        String urlString = String.format(API_URL_TEMPLATE, chatId);
        String boundary = "*****" + System.currentTimeMillis() + "*****";
        String twoHyphens = "--";
        String lineEnd = "\r\n";

        try {
            Slog.d(TAG, "Sending logs to: " + urlString);
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Connection", "Keep-Alive");
            connection.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);

            DataOutputStream dos = new DataOutputStream(connection.getOutputStream());


            if (caption != null) {
                dos.writeBytes(twoHyphens + boundary + lineEnd);
                dos.writeBytes("Content-Disposition: form-data; name=\"caption\"" + lineEnd);
                dos.writeBytes(lineEnd);


                dos.write(caption.getBytes("UTF-8"));
                dos.writeBytes(lineEnd);
            }


            dos.writeBytes(twoHyphens + boundary + lineEnd);
            dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + logFile.getName() + "\"" + lineEnd);
            dos.writeBytes("Content-Type: text/plain" + lineEnd);
            dos.writeBytes(lineEnd);

            FileInputStream fis = new FileInputStream(logFile);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
            }
            fis.close();
            dos.writeBytes(lineEnd);
            dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
            dos.flush();
            dos.close();

            int responseCode = connection.getResponseCode();
            if (responseCode == 200 || responseCode == 201) {
                Slog.d(TAG, "Logs sent successfully: " + responseCode);
                return null;
            } else {
                 String errorMsg = "HTTP " + responseCode;
                 try {
                     BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                     String line;
                     StringBuilder response = new StringBuilder();
                     while ((line = br.readLine()) != null) {
                         response.append(line);
                     }
                     br.close();
                     errorMsg += ": " + response.toString();
                     Slog.e(TAG, "Failed to send logs. " + errorMsg);
                 } catch (Exception e) {
                     Slog.e(TAG, "Failed to send logs. Response Code: " + responseCode);
                 }
                 return errorMsg;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error sending logs: " + e.getMessage(), e);
            return "Exception: " + e.getMessage();
        }
    }

    public static String sendToWebhook(String webhookUrl, File logFile, String summary) {
        if (webhookUrl == null || !webhookUrl.contains(WEBHOOK_API_PREFIX)) {
            return "Invalid webhook";
        }
        if (logFile == null || !logFile.exists()) {
            return "Log file not found";
        }
        String heading = summary == null ? "" : summary;
        String mediaId = uploadMedia(webhookUrl, logFile);
        if (mediaId != null) {
            String error = sendFileMessage(webhookUrl, mediaId);
            if (error == null) {
                sendTextMessage(webhookUrl, heading);
                return null;
            }
            Slog.w(TAG, "File message failed, falling back to text: " + error);
        }
        return sendTextMessage(webhookUrl, heading + "\n\n" + tail(logFile, MAX_TEXT_BYTES));
    }

    private static String endpoint(String webhookUrl, String action, String extra) {
        int prefix = webhookUrl.indexOf(WEBHOOK_API_PREFIX);
        String base = webhookUrl.substring(0, prefix);
        String query = "";
        int mark = webhookUrl.indexOf('?', prefix);
        if (mark >= 0) {
            query = webhookUrl.substring(mark + 1);
        }
        StringBuilder url = new StringBuilder(base).append(WEBHOOK_API_PREFIX).append(action);
        if (!query.isEmpty()) {
            url.append('?').append(query);
        }
        if (extra != null && !extra.isEmpty()) {
            url.append(query.isEmpty() ? '?' : '&').append(extra);
        }
        return url.toString();
    }

    private static String uploadMedia(String webhookUrl, File logFile) {
        String boundary = "*****" + System.currentTimeMillis() + "*****";
        String lineEnd = "\r\n";
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint(webhookUrl, UPLOAD_END, "type=file"));
            connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Connection", "Keep-Alive");
            connection.setRequestProperty("Content-Type",
                    "multipart/form-data;boundary=" + boundary);

            DataOutputStream dos = new DataOutputStream(connection.getOutputStream());
            dos.writeBytes("--" + boundary + lineEnd);
            dos.writeBytes("Content-Disposition: form-data; name=\"media\"; filename=\""
                    + logFile.getName() + "\"" + lineEnd);
            dos.writeBytes("Content-Type: application/octet-stream" + lineEnd);
            dos.writeBytes(lineEnd);
            FileInputStream fis = new FileInputStream(logFile);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
            }
            fis.close();
            dos.writeBytes(lineEnd);
            dos.writeBytes("--" + boundary + "--" + lineEnd);
            dos.flush();
            dos.close();

            JSONObject reply = readJson(connection);
            if (reply == null || reply.optInt("errcode", -1) != 0) {
                Slog.e(TAG, "Upload failed: " + reply);
                return null;
            }
            String mediaId = reply.optString("media_id", "");
            return mediaId.isEmpty() ? null : mediaId;
        } catch (Exception e) {
            Slog.e(TAG, "Error uploading log: " + e.getMessage(), e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String sendFileMessage(String webhookUrl, String mediaId) {
        try {
            JSONObject file = new JSONObject().put("media_id", mediaId);
            JSONObject body = new JSONObject().put("msgtype", "file").put("file", file);
            return postJson(webhookUrl, body.toString());
        } catch (Exception e) {
            return "Exception: " + e.getMessage();
        }
    }

    private static String sendTextMessage(String webhookUrl, String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        try {
            JSONObject text = new JSONObject().put("content", truncate(content, MAX_TEXT_BYTES));
            JSONObject body = new JSONObject().put("msgtype", "text").put("text", text);
            return postJson(webhookUrl, body.toString());
        } catch (Exception e) {
            return "Exception: " + e.getMessage();
        }
    }

    private static String postJson(String webhookUrl, String payload) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint(webhookUrl, SEND_END, ""));
            connection = (HttpURLConnection) url.openConnection();
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            DataOutputStream dos = new DataOutputStream(connection.getOutputStream());
            dos.write(bytes);
            dos.flush();
            dos.close();

            JSONObject reply = readJson(connection);
            if (reply == null || reply.optInt("errcode", -1) != 0) {
                String errorMsg = reply == null ? "empty reply" : reply.toString();
                Slog.e(TAG, "Webhook send failed: " + errorMsg);
                return errorMsg;
            }
            return null;
        } catch (Exception e) {
            Slog.e(TAG, "Error sending to webhook: " + e.getMessage(), e);
            return "Exception: " + e.getMessage();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static JSONObject readJson(HttpURLConnection connection) {
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = stream.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            stream.close();
            return new JSONObject(out.toString("UTF-8"));
        } catch (Exception e) {
            Slog.e(TAG, "Cannot read reply: " + e.getMessage(), e);
            return null;
        }
    }

    private static String truncate(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
    }

    private static String tail(File logFile, int maxBytes) {
        try {
            long length = logFile.length();
            int want = (int) Math.min(length, maxBytes);
            FileInputStream fis = new FileInputStream(logFile);
            long skipped = 0;
            while (skipped < length - want) {
                long n = fis.skip(length - want - skipped);
                if (n <= 0) break;
                skipped += n;
            }
            byte[] bytes = new byte[want];
            int read = 0;
            while (read < want) {
                int n = fis.read(bytes, read, want - read);
                if (n < 0) break;
                read += n;
            }
            fis.close();
            return new String(bytes, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Slog.e(TAG, "Cannot read log file: " + e.getMessage(), e);
            return "";
        }
    }
}
