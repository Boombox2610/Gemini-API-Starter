package com.fahim.geminiapistarter;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fahim.geminiapistarter.adapter.ChatAdapter;
import com.fahim.geminiapistarter.model.ChatMessage;
import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;

public class MainActivity extends AppCompatActivity {

    private EditText promptEditText;
    private ProgressBar progressBar;
    private RecyclerView chatRecyclerView;
    private ChatAdapter chatAdapter;
    private TextView emptyStateTextView;
    private ImageButton micButton; // Added for microphone

    private List<ChatMessage> chatHistory;
    private Gson gson;
    private static final String CHAT_HISTORY_FILE = "chathistory.json";
    private GenerativeModel generativeModel;

    private static final SimpleDateFormat archiveTimestampFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());

    private ActivityResultLauncher<Intent> speechRecognitionLauncher;
    private ActivityResultLauncher<String> requestPermissionLauncher;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        promptEditText = findViewById(R.id.promptEditText);
        ImageButton submitPromptButton = findViewById(R.id.sendButton);
        progressBar = findViewById(R.id.progressBar);
        chatRecyclerView = findViewById(R.id.chatRecyclerView);
        emptyStateTextView = findViewById(R.id.emptyStateTextView);
        micButton = findViewById(R.id.micButton); // Initialize mic button

        gson = new Gson();
        chatHistory = new ArrayList<>();

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        chatRecyclerView.setLayoutManager(layoutManager);
        chatAdapter = new ChatAdapter(chatHistory);
        chatRecyclerView.setAdapter(chatAdapter);

        loadChatHistory();

        generativeModel = new GenerativeModel("gemini-1.5-flash", // Use a valid model name
                BuildConfig.API_KEY);

        // Initialize the permission launcher
        requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                launchSpeechRecognizer();
            } else {
                Toast.makeText(this, R.string.permission_denied_speech, Toast.LENGTH_SHORT).show();
            }
        });

        // Initialize the speech recognition launcher
        speechRecognitionLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                ArrayList<String> Fresults = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (Fresults != null && !Fresults.isEmpty()) {
                    promptEditText.setText(Fresults.get(0));
                }
            }
        });

        micButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                launchSpeechRecognizer();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            }
        });


        submitPromptButton.setOnClickListener(v -> {
            String prompt = promptEditText.getText().toString().trim();
            promptEditText.setError(null);

            if (prompt.isEmpty()) {
                promptEditText.setError(getString(R.string.field_cannot_be_empty));
                return;
            }

            ChatMessage userMessage = new ChatMessage("user", prompt);
            chatHistory.add(userMessage);
            updateChatDisplay();
            saveChatHistory();

            promptEditText.setText("");
            progressBar.setVisibility(VISIBLE);

            generativeModel.generateContent(prompt, new Continuation<>() {
                @NonNull
                @Override
                public CoroutineContext getContext() {
                    return EmptyCoroutineContext.INSTANCE;
                }

                @Override
                public void resumeWith(@NonNull Object o) {
                    if (o instanceof Throwable) {
                        Throwable throwable = (Throwable) o;
                        Log.e("GeminiResponse", "Error generating content", throwable);
                        runOnUiThread(() -> {
                            progressBar.setVisibility(GONE);
                            ChatMessage errorMessage = new ChatMessage("model", "Error: " + throwable.getMessage());
                            chatHistory.add(errorMessage);
                            updateChatDisplay();
                            saveChatHistory();
                        });
                        return;
                    }

                    if (o instanceof GenerateContentResponse) {
                        GenerateContentResponse response = (GenerateContentResponse) o;
                        String responseString = response.getText();

                        if (responseString != null) {
                            Log.d("GeminiResponse", responseString);
                            ChatMessage modelMessage = new ChatMessage("model", TextFormatter.formatText(responseString).toString());
                            chatHistory.add(modelMessage);

                            runOnUiThread(() -> {
                                progressBar.setVisibility(GONE);
                                updateChatDisplay();
                                saveChatHistory();
                            });
                        } else {
                            Log.e("GeminiResponse", "Response text is null");
                            ChatMessage errorMessage = new ChatMessage("model", "Received an empty response from the model.");
                            chatHistory.add(errorMessage);
                            runOnUiThread(() -> {
                                progressBar.setVisibility(GONE);
                                updateChatDisplay();
                                saveChatHistory();
                            });
                        }
                    } else {
                        Log.e("GeminiResponse", "Unexpected response type: " + o.getClass().getName());
                        ChatMessage errorMessage = new ChatMessage("model", "Received an unexpected response type.");
                        chatHistory.add(errorMessage);
                        runOnUiThread(() -> {
                            progressBar.setVisibility(GONE);
                            updateChatDisplay();
                            saveChatHistory();
                        });
                    }
                }
            });
        });
        updateChatDisplay();
    }

    private void launchSpeechRecognizer() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speech_prompt_message));
        try {
            speechRecognitionLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.speech_not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadChatHistory() {
        File file = new File(getFilesDir(), CHAT_HISTORY_FILE);
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                Type type = new TypeToken<ArrayList<ChatMessage>>() {}.getType();
                List<ChatMessage> loadedHistory = gson.fromJson(reader, type);
                if (loadedHistory != null) {
                    chatHistory.clear();
                    chatHistory.addAll(loadedHistory);
                }
            } catch (IOException e) {
                Log.e("ChatHistory", "Error loading chat history", e);
                Toast.makeText(this, "Could not load chat history.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void saveChatHistory() {
        File file = new File(getFilesDir(), CHAT_HISTORY_FILE);
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(chatHistory, writer);
        } catch (IOException e) {
            Log.e("ChatHistory", "Error saving chat history", e);
            Toast.makeText(this, "Could not save chat history.", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateChatDisplay() {
        if (chatHistory.isEmpty()) {
            chatRecyclerView.setVisibility(View.GONE);
            emptyStateTextView.setVisibility(View.VISIBLE);
        } else {
            chatRecyclerView.setVisibility(View.VISIBLE);
            emptyStateTextView.setVisibility(View.GONE);
            chatAdapter.notifyDataSetChanged();
            chatRecyclerView.scrollToPosition(chatHistory.size() - 1);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_new_chat) {
            archiveCurrentChatAndStartNew();
            return true;
        } else if (itemId == R.id.action_delete_current_chat) {
            deleteCurrentChat();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void archiveCurrentChatAndStartNew() {
        File currentChatFile = new File(getFilesDir(), CHAT_HISTORY_FILE);
        if (currentChatFile.exists() && !chatHistory.isEmpty()) {
            String timestamp = archiveTimestampFormat.format(new Date());
            String archiveFileName = "chat_archive_" + timestamp + ".json";
            File archiveFile = new File(getFilesDir(), archiveFileName);
            if (currentChatFile.renameTo(archiveFile)) {
                Log.i("ChatHistory", "Current chat archived to " + archiveFileName);
                Toast.makeText(this, "Chat archived", Toast.LENGTH_SHORT).show();
            } else {
                Log.e("ChatHistory", "Could not archive current chat.");
                Toast.makeText(this, "Could not archive chat.", Toast.LENGTH_SHORT).show();
            }
        }
        chatHistory.clear();
        if (currentChatFile.exists()) {
             if (!currentChatFile.delete()) {
                 Log.e("ChatHistory", "Could not delete old current chat file for new session.");
             }
        }
        updateChatDisplay();
        Toast.makeText(this, "New chat started", Toast.LENGTH_SHORT).show();
    }

    private void deleteCurrentChat() {
        chatHistory.clear();
        File file = new File(getFilesDir(), CHAT_HISTORY_FILE);
        if (file.exists()) {
            if (file.delete()) {
                Log.i("ChatHistory", "Current chat history file deleted.");
                Toast.makeText(this, "Chat history deleted", Toast.LENGTH_SHORT).show();
            } else {
                Log.e("ChatHistory", "Could not delete chat history file.");
                Toast.makeText(this, "Could not delete chat history.", Toast.LENGTH_SHORT).show();
            }
        } else {
             Toast.makeText(this, "Chat history cleared", Toast.LENGTH_SHORT).show();
        }
        updateChatDisplay();
    }
}
