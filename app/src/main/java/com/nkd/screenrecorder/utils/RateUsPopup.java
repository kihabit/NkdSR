package com.nkd.screenrecorder.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RatingBar;
import android.widget.TextView;
import android.widget.Toast;

import com.nkd.screenrecorder.R;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;

public class RateUsPopup {

    public static View.OnClickListener showRateDialog(Context context) {
        // Inflate custom layout
        LayoutInflater inflater = LayoutInflater.from(context);
        View rateView = inflater.inflate(R.layout.dialog_rate_us, null);

        RatingBar ratingBar = rateView.findViewById(R.id.ratingBar);
        TextView rateTitle = rateView.findViewById(R.id.rateTitle);
        Button submitButton = rateView.findViewById(R.id.submit_button);
        //ImageView closeButton = rateView.findViewById(R.id.close_button);

        // Set the RatingBar change listener
        ratingBar.setOnRatingBarChangeListener((ratingBar1, rating, fromUser) -> {
            if (rating == 5) {
                submitButton.setText(R.string.rate_on_play_store);
                rateTitle.setText(R.string.rate_title_5_star);
            } else {
                submitButton.setText(R.string.submit_feedback);
                rateTitle.setText(R.string.rate_title_low);
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(rateView)
                .create();

        // Close button listener (cross button)
        //closeButton.setOnClickListener(v -> dialog.dismiss());

        // Set the behavior for the submit button
        submitButton.setOnClickListener(v -> {

            int rating = (int) ratingBar.getRating();
            DBHelper dbHelper = new DBHelper(context.getApplicationContext());

            // Check if rating exists
            if (!dbHelper.isRatingExists()) {
                // Save rating
                dbHelper.insertRating((int) rating); // Example rating

            } else {
                int ExitingRating = dbHelper.getRating();
                // Do something with the saved rating
                if(ExitingRating < 5) {
                    dbHelper.updateRating(rating);

                }
            }
            if (rating == 0) {
                Toast.makeText(context, R.string.rate_select_rating, Toast.LENGTH_SHORT).show();
                return;
            }

            if (rating == 5) {
                // Change Submit button text to "Rate on Play Store" if rating is 5


                // Redirect to Play Store if rating is 5
                submitButton.setOnClickListener(v1 -> {
                    try {
                        context.startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=com.nkd.screenrecorder&hl=en_IN")));
                        dialog.dismiss();
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(context, R.string.play_store_unavailable, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    }
                });

            } else {
                // Handle feedback dialog for ratings less than 5
                showFeedbackDialog(context);
                dialog.dismiss();
            }
        });

        dialog.setOnCancelListener(d -> {
            DBHelper db = new DBHelper(context.getApplicationContext());
            if (!db.isRatingExists()) {
                db.insertRating(0); // 0 = ignored
            }
            if (context instanceof Activity) {
                try {
                    java.lang.reflect.Field field =
                            context.getClass().getField("rateDialogIgnored");
                    field.set(context, true);

                    java.lang.reflect.Field countField =
                            context.getClass().getField("globalClickCount");
                    countField.set(context, 0);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        dialog.show();
        return null;
    }

    private static boolean isInternetAvailable(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        }
        return false;
    }

    private static void showFeedbackDialog(Context context) {
        final int MAX_CHAR = 200;

        // Main layout
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);

        // Input layout with EditText + Mic
        LinearLayout inputLayout = new LinearLayout(context);
        inputLayout.setOrientation(LinearLayout.HORIZONTAL);
        inputLayout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // EditText for feedback
        final EditText input = new EditText(context);
        input.setHint(R.string.feedback_hint);
        input.setMinLines(3);
        input.setMaxLines(5);
        input.setSingleLine(false);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_CHAR)});

        // Mic button
        final ImageView micButton = new ImageView(context);
        micButton.setImageResource(R.drawable.ic_mic_idle);
        micButton.setPadding(16, 16, 16, 16);
        micButton.setClickable(true);

        inputLayout.addView(input);
        inputLayout.addView(micButton);

        // Character counter
        final TextView counter = new TextView(context);
        counter.setText(context.getString(R.string.feedback_counter, 0, MAX_CHAR));
        counter.setPadding(0, 10, 0, 0);

        // Mic status message
        final TextView micStatus = new TextView(context);
        micStatus.setText("");
        micStatus.setTextColor(Color.GRAY);
        micStatus.setPadding(0, 10, 0, 0);
        micStatus.setTextSize(14);

        // Add views
        layout.addView(inputLayout);
        layout.addView(counter);
        layout.addView(micStatus);

        // Text counter
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                counter.setText(context.getString(R.string.feedback_counter, s.length(), MAX_CHAR));
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Build dialog
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.feedback_title)
                .setView(layout)
                .setPositiveButton(R.string.submit, null)
                .create();

        dialog.setOnShowListener(d -> {
            Button submitButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (submitButton != null) {
                submitButton.setOnClickListener(v -> {
                    String feedback = input.getText().toString().trim();
                    if (!feedback.isEmpty()) {
                        if (isInternetAvailable(context)) {
                            new Thread(() -> {
                                try {
                                    String name = "Screen recorder";
                                    String encodedFeedback = URLEncoder.encode(feedback, "UTF-8");
                                    String urlString = "https://multiappstudio.com/appapi/bvr_api.php?name="
                                            + URLEncoder.encode(name, "UTF-8")
                                            + "&feedback=" + encodedFeedback;

                                    URL url = new URL(urlString);
                                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                                    conn.setRequestMethod("GET");
                                    conn.setConnectTimeout(5000);
                                    conn.setReadTimeout(5000);

                                    int responseCode = conn.getResponseCode();

                                    if (responseCode == HttpURLConnection.HTTP_OK) {
                                        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                                        StringBuilder response = new StringBuilder();
                                        String inputLine;
                                        while ((inputLine = in.readLine()) != null) {
                                            response.append(inputLine);
                                        }
                                        in.close();
                                        ((Activity) context).runOnUiThread(() -> {
                                            Toast.makeText(context, R.string.feedback_thanks, Toast.LENGTH_SHORT).show();
                                            dialog.dismiss();
                                        });
                                    } else {
                                        ((Activity) context).runOnUiThread(() -> {
                                            Toast.makeText(context, R.string.feedback_failed, Toast.LENGTH_SHORT).show();
                                        });
                                    }

                                    conn.disconnect();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    ((Activity) context).runOnUiThread(() -> {
                                        Toast.makeText(context, R.string.feedback_thanks, Toast.LENGTH_SHORT).show();
                                        dialog.dismiss();
                                    });
                                }
                            }).start();
                        } else {
                            Toast.makeText(context, R.string.feedback_thanks, Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                        }
                    } else {
                        input.setError(context.getString(R.string.feedback_empty));
                    }
                });
            }
        });

        // Setup speech recognizer
        SpeechRecognizer speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        Intent speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()); // app language, as a tag string

        micButton.setOnClickListener(v -> {
            // Check mic permission (needs to be Activity context)

            micButton.setImageResource(R.drawable.ic_mic_active);
            micStatus.setText(R.string.mic_listening);

            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}

                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}

                @Override public void onEndOfSpeech() {
                    micButton.setImageResource(R.drawable.ic_mic_idle);
                    micStatus.setText(R.string.mic_processing);
                }

                @Override public void onError(int error) {
                    micButton.setImageResource(R.drawable.ic_mic_idle);
                    micStatus.setText(R.string.mic_error);
                }

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String spokenText = matches.get(0);
                        input.setText(spokenText);
                        input.setSelection(spokenText.length());
                        micStatus.setText(R.string.mic_recognized);
                    }
                    micButton.setImageResource(R.drawable.ic_mic_idle);
                }

                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });

            speechRecognizer.startListening(speechIntent);
        });

        dialog.show();
    }

}
