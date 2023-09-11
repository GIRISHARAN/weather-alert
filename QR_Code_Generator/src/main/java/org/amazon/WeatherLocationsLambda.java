package org.amazon;

import com.amazonaws.lambda.thirdparty.org.json.JSONArray;
import com.amazonaws.lambda.thirdparty.org.json.JSONObject;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.errors.ApiException;
import com.google.maps.model.GeocodingResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class WeatherLocationsLambda implements RequestHandler<Map<String, Object>, String> {

    private static final String TABLE_NAME = "Weather_Locations_Test";
//    private static final String TABLE_NAME = "Weather_Locations";
    
    private static final String Visual_Crossing_API_KEY = "A6DMKAL4DCE4QFDZKSVA3FRAH";

    public static double fahrenheitToCelsius(double fahrenheitTemp) {
        double celsiusTemp = (fahrenheitTemp - 32) * 5 / 9;
        return Math.round(celsiusTemp * 100.0) / 100.0;
    }

    private static String convertEpochToTime(long epochSeconds) {
        Date date = new Date(epochSeconds * 1000);
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        return sdf.format(date);
    }

    public static String parseWeatherData(String jsonData) {
        JSONObject weatherData = new JSONObject(jsonData);

        // Get the days array from the JSON data
        JSONArray daysArray = weatherData.getJSONArray("days");
        int numHours = daysArray.getJSONObject(0).getJSONArray("hours").length();

        // Variables to calculate average temperature and feels-like temperature in Celsius
        double tempSumCelsius = 0.0;
        double feelsLikeSumCelsius = 0.0;

        // Loop through the hours array to calculate the sums in Celsius
        for (int i = 0; i < numHours; i++) {
            JSONObject hourData = daysArray.getJSONObject(0).getJSONArray("hours").getJSONObject(i);
            double tempFahrenheit = hourData.getDouble("temp");
            double feelsLikeFahrenheit = hourData.getDouble("feelslike");
            tempSumCelsius += fahrenheitToCelsius(tempFahrenheit);
            feelsLikeSumCelsius += fahrenheitToCelsius(feelsLikeFahrenheit);
        }

        // Calculate average temperature and feels-like temperature in Celsius
        double avgTempCelsius = Double.parseDouble(new DecimalFormat("##.####").format(tempSumCelsius / numHours));;
        double avgFeelsLikeCelsius = Double.parseDouble(new DecimalFormat("##.####").format(feelsLikeSumCelsius / numHours));

        // Get today's sunrise and sunset times
        String sunriseTime = weatherData.getJSONObject("currentConditions").getString("sunrise") + "am";
        String sunsetTime = weatherData.getJSONObject("currentConditions").getString("sunset") + "pm";



        // Get any alerts if present
        JSONArray alertsArray = weatherData.getJSONArray("alerts");
        String alerts = "";
        if (alertsArray.length() > 0) {
            JSONObject alert = alertsArray.getJSONObject(0);
            alerts = alert.getString("description");
        }

        // Construct the final result string
        String result = "Average Temperature: " + avgTempCelsius + "°C\n" + "Average Feels-Like Temperature: " + avgFeelsLikeCelsius + "°C\n" + "Sunrise Time: " + sunriseTime + "\n" + "Sunset Time: " + sunsetTime + "\n" + "Alerts: " + alerts;

        return result;
    }

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {

        String awsRegion = "us-east-1";
        String tableName = "Weather_Locations_Test";
//        String tableName = "Weather_Locations";
        String queueUrl = "https://sqs.us-east-1.amazonaws.com/923540452677/Weather-SQS-Test";
//        String queueUrl = "https://sqs.us-east-1.amazonaws.com/283491128229/secondQueue";

        try {

            ObjectMapper objectMapper = new ObjectMapper();

            // Create an AmazonDynamoDB client
            AmazonDynamoDB client = AmazonDynamoDBClientBuilder.standard().withRegion(awsRegion).build();

            // Create a DynamoDB instance
            DynamoDB dynamoDB = new DynamoDB(client);

            // Get the table by its name
            Table table = dynamoDB.getTable(tableName);

            // Perform a scan operation to get all items in the table
            ItemCollection<ScanOutcome> items = table.scan();


            // Iterate through the items and process them as needed
            for (Item item : items) {
                String placeName = item.getString("location");
                String gmail = item.getString("gmail");

                // Set up the GeoApiContext with your Google Maps API key
                GeoApiContext geoApiContext = new GeoApiContext.Builder().apiKey(GOOGLE_MAPS_API_KEY).build();

                // Perform the geocoding request to get latitude and longitude
                GeocodingResult[] results = GeocodingApi.geocode(geoApiContext, placeName).await();

                if (results.length > 0) {
                    double latitude = results[0].geometry.location.lat;
                    double longitude = results[0].geometry.location.lng;

                    // Hit OpenWeatherMap API with latitude and longitude
                    String openWeatherMapUrl = "https://weather.visualcrossing.com/VisualCrossingWebServices/rest/services/timeline/" + latitude + "," + longitude + "/today?key=" + Visual_Crossing_API_KEY;
                    URL url = new URL(openWeatherMapUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                    // Read the response from the API
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    String line;
                    StringBuilder weatherResponse = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        weatherResponse.append(line);
                    }
                    reader.close();

                    // Shut down the GeoApiContext to release resources
                    geoApiContext.shutdown();

                    String extractedWeatherData = parseWeatherData(weatherResponse.toString())+"\nYour Location: "+placeName;

                    System.out.println(extractedWeatherData);

                    // Prepare the message in the required format
                    Map<String, Object> messageData = new HashMap<>();
                    messageData.put("gmail", gmail);
                    messageData.put("Temperature", extractedWeatherData);

                    // Convert the messageData map to JSON format
                    String jsonString = new ObjectMapper().writeValueAsString(messageData);


                    // Create SQS client
                    AmazonSQS sqsClient = AmazonSQSClientBuilder.defaultClient();

                    SendMessageRequest sendMessageRequest = new SendMessageRequest()
                            .withQueueUrl(queueUrl)
                            .withMessageBody(jsonString);

                    sqsClient.sendMessage(sendMessageRequest);
                    System.out.println("Weather data sent to SQS queue.");

                }
            }
        } catch (ApiException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}