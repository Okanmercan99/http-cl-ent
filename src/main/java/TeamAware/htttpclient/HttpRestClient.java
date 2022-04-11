package TeamAware.htttpclient;

import lombok.extern.slf4j.Slf4j;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

@Slf4j
@Component
@EnableScheduling
public class HttpRestClient {
    static final Map<String, Properties> realmProperties = new HashMap<>();
    static Map<String, Map<String, String>> realmAccessTokens = new HashMap<>();

    private static void loadConPropsFromClasspath() throws Exception {
        File folder = new File("src/main/resources/realms");
        File[] listOfRealmConfigFiles = folder.listFiles();

        for (int i = 0; i < listOfRealmConfigFiles.length; i++) {
            if (listOfRealmConfigFiles[i].isFile() && listOfRealmConfigFiles[i].getName().endsWith("properties")) {
                log.info("Configuration File " + listOfRealmConfigFiles[i].getName());

                InputStream stream = new FileInputStream(listOfRealmConfigFiles[i]);

                Properties props = new Properties();
                props.load(stream);
                stream.close();
                log.info("Configuration " + props);
                realmProperties.put(listOfRealmConfigFiles[i].getName().split("\\.")[0], props);
            }
        }
    }

    public static String sendHttpMessage(String realm, String requestedResource, String message, String requestMethod)
            throws IOException {

        URL url = new URL("http", realmProperties.get(realm).getProperty("resource.endpoint"),
                Integer.parseInt(realmProperties.get("master").getProperty("resource.port")),
                "/api/" + requestedResource);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();

        if (requestMethod.trim().equals("GET")) {
            con.setRequestMethod(requestMethod);
            con.setRequestProperty("User-Agent", "Mozilla/5.0");
        }

        if (requestedResource.trim().equals("ADSData")) {
            con.setRequestProperty("Content-Type", "application/xml; charset=UTF-8");
        } else {
            con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        }
        con.setRequestProperty("Accept", "application/json");

        // Set Access Token
        String accessToken = realmAccessTokens.get("master").get(requestedResource.split("Data")[0]);
        con.setRequestProperty("Authorization", "Bearer " + accessToken);

        if (requestMethod.trim().equals("POST") || requestMethod.trim().equals("PATCH")) {
            con.setDoOutput(true);
            con.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            con.setRequestMethod("POST");

            try (OutputStream os = con.getOutputStream()) {
                byte[] input = message.getBytes("utf-8");
                os.write(input, 0, input.length);
            }
        }
        log.info("Request is sent...");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String responseLine = null;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            log.info("Response :\n" + response.toString());
            return response.toString();
        }
    }

    private static String authenticateForRealm(String clientId, Properties props) throws Exception {
        String realmId = props.getProperty("realm.id").trim();
        String targetClientId = props.getProperty("target.client.id").trim();
        String keycloakEndpoint = props.getProperty("keycloak.endpoint");
        int keycloakPort = Integer.parseInt(props.getProperty("keycloak.port"));
        String clientSecret = props.getProperty(clientId + ".client.secret").trim();
        URL url = new URL("http", keycloakEndpoint, keycloakPort,
                        "/auth/realms/" + realmId + "/protocol/openid-connect/token");
        //Get Realm Access Token
        HttpURLConnection con = (HttpURLConnection) url.openConnection();

        log.info("Authenticating Client: " + clientId + " with client secret: " + clientSecret);

        String plainCredentials = clientId + ":" + clientSecret;
        String base64Credentials = new String(Base64.getEncoder().encode(plainCredentials.getBytes()));
        // Create authorization header
        String authorizationHeader = "Basic " + base64Credentials;

        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        con.setRequestProperty("Authorization", authorizationHeader);

        con.setDoOutput(true);
        con.setRequestMethod("POST");

        String message = "grant_type=client_credentials&audience=" + targetClientId;

        try (OutputStream os = con.getOutputStream()) {
            byte[] input = message.getBytes("utf-8");
            os.write(input, 0, input.length);
        }

        String realmAccessToken = "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String responseLine = null;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            String responseJsonString = response.toString();
            JSONObject responseJson = new JSONObject(responseJsonString);
            realmAccessToken = responseJson.getString("access_token");
        }
        log.info(" Realm Access Token = " + realmAccessToken);
        return realmAccessToken;
    }

    @Scheduled(fixedRate=3600000)
    public static void populateAccessTokens() {
        try {
            if (realmProperties.isEmpty())
                loadConPropsFromClasspath();

            for (String key : realmProperties.keySet()) {
                Properties props = realmProperties.get(key);

                Map<String, String> accessTokens = realmAccessTokens.get(key);

                if (accessTokens == null)
                    accessTokens = new HashMap<>();

                String[] realmClients = props.getProperty("realm.clients").split(";");

                for (String realmClient : realmClients) {
                    accessTokens.put(realmClient, authenticateForRealm(realmClient, props));
                }

                realmAccessTokens.put(key, accessTokens);
            }
            log.info("Access tokens gathered.");
        } catch (Exception ex) {
            log.error("", ex);
        }
    }

    @Scheduled(initialDelay = 10000, fixedRate=10000)
    public static void ADSClient() throws Exception {
        //XML Document created
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document document = dBuilder.newDocument();

        //Prepare a observation
        log.info("ADS Client preparing an observation...");
        //<message name = "ADS">
        Element rootElement = document.createElement("message");
        rootElement.setAttribute("name", "ADS");
        document.appendChild(rootElement);

        //<msgHeader>
        Element messageHeaderElement = document.createElement("msgHeader");

        //<msgID>
        Element messageIdElement = document.createElement("msgID");
        messageIdElement.appendChild(document.createTextNode(Integer.toString(HtttpClientApplication.adsMessageId)));
        HtttpClientApplication.adsMessageId++;
        messageHeaderElement.appendChild(messageIdElement);

        //<msgSender>
        Element messageSenderElement = document.createElement("msgSender");
        messageSenderElement.appendChild(document.createTextNode("A"));
        messageHeaderElement.appendChild(messageSenderElement);

        //<msgReciever>
        Element messageRecieverElement = document.createElement("msgReciever");
        messageRecieverElement.appendChild(document.createTextNode("B"));
        messageHeaderElement.appendChild(messageRecieverElement);

        //</msgHeader>
        rootElement.appendChild(messageHeaderElement);

        //<drone_id>
        Element droneIdElement = document.createElement("drone_id");
        droneIdElement.appendChild(document.createTextNode("1"));
        rootElement.appendChild(droneIdElement);

        //<timestamp>
        Element timestampElement = document.createElement("timestamp");
        timestampElement
                .appendChild(document.createTextNode(new SimpleDateFormat("yyyy-MM-dd.HH.mm.ss").format(new Date())));
        rootElement.appendChild(timestampElement);

        //<audio_detector>
        Element audioDetectorElement = document.createElement("audio_detector");

        //<detection>
        Element detectionElement = document.createElement("detection");

        //<labels>
        Element labelsElement = document.createElement("labels");
        labelsElement.appendChild(document.createTextNode("AUDIO TEST"));
        detectionElement.appendChild(labelsElement);

        //<confidence>
        Element confidenceElement = document.createElement("confidence");
        confidenceElement.appendChild(document.createTextNode(Float.valueOf(new Random().nextFloat()).toString()));
        detectionElement.appendChild(confidenceElement);

        //<azimuth_pred>
        Element azimuthPredElement = document.createElement("azimuth_pred");
        azimuthPredElement.appendChild(document.createTextNode(Float.valueOf(new Random().nextFloat()).toString()));
        detectionElement.appendChild(azimuthPredElement);

        //<elevation_pred>
        Element elevationPredElement = document.createElement("elevation_pred");
        elevationPredElement.appendChild(document.createTextNode(Float.valueOf(new Random().nextFloat()).toString()));
        detectionElement.appendChild(elevationPredElement);

        //</detection>
        audioDetectorElement.appendChild(detectionElement);

        //</audio_detectorr>
        rootElement.appendChild(audioDetectorElement);

        //<image_detector>
        Element imageDetectorElement = document.createElement("image_detector");

        //<detection>
        detectionElement = document.createElement("detection");

        //<labels>
        labelsElement = document.createElement("labels");
        labelsElement.appendChild(document.createTextNode("IMAGE TEST"));
        detectionElement.appendChild(labelsElement);

        //<confidence>
        confidenceElement = document.createElement("confidence");
        confidenceElement.appendChild(document.createTextNode(Float.valueOf(new Random().nextFloat()).toString()));
        detectionElement.appendChild(confidenceElement);

        //<latitude>
        Element latitudeElement = document.createElement("latitude");
        latitudeElement.appendChild(document.createTextNode(Float.valueOf(new Random().nextFloat() * 10).toString()));
        detectionElement.appendChild(latitudeElement);

        //<longitude>
        Element longitudeElement = document.createElement("longitude");
        longitudeElement.appendChild(document.createTextNode(Float.valueOf(new Random().nextFloat() * 10).toString()));
        detectionElement.appendChild(longitudeElement);

        //</detection>
        imageDetectorElement.appendChild(detectionElement);

        //</image_detector>
        rootElement.appendChild(imageDetectorElement);

        //<late_fusion_result>
        Element lateFusionResultElement = document.createElement("late_fusion_result");

        //<detection>
        detectionElement = document.createElement("detection");

        //<label>
        labelsElement = document.createElement("label");
        labelsElement.appendChild(document.createTextNode("LATE FUSION TEST"));
        detectionElement.appendChild(labelsElement);

        //<confidence>
        confidenceElement = document.createElement("confidence");
        confidenceElement.appendChild(document.createTextNode(Float.valueOf(new Random().nextFloat()).toString()));
        detectionElement.appendChild(confidenceElement);

        //</detection>
        lateFusionResultElement.appendChild(detectionElement);

        //</late_fusion_result>
        rootElement.appendChild(lateFusionResultElement);

        //Transform to xml format
        DOMSource source = new DOMSource(document);
        StringWriter writer = new StringWriter();
        StreamResult result = new StreamResult(writer);
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.transform(source, result);
        String message = writer.toString();
        log.info("ADS Observation is ready: " + message);

        //Send it
        sendHttpMessage("master", "ADSData", message, "POST");
    }
    
    @Scheduled(initialDelay = 10000, fixedRate=10000)
    public static void VSASClient() throws Exception {

        log.info("VSAS Client preparing an observation...");

        JSONObject message = new JSONObject();

        JSONArray responders = new JSONArray();

        for (int i = 0; i < 5; i++) {

            JSONObject responder = new JSONObject();

            responder.put("id", i);

            JSONArray position = new JSONArray();
            position.put(Float.valueOf(new Random().nextFloat()));
            position.put(Float.valueOf(new Random().nextFloat()));
            position.put(Float.valueOf(new Random().nextFloat()));

            responder.put("position", position);

            responders.put(responder);
        }

        message.put("responders", responders);
        log.info("VSAS Observation is ready: " + message.toString());

        sendHttpMessage("master", "VSASData", message.toString(), "POST");
    }
}


