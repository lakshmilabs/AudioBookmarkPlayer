package com.audiobookmark.player;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "AudioBookmarkPrefs";
    private static final String PREF_SELECTED_ACCOUNT = "selected_account";

    private MediaPlayer mediaPlayer;
    private TextView fileNameText;
    private TextView currentTimeText;
    private TextView durationText;
    private TextView speedText;
    private SeekBar seekBar;
    private MaterialButton playPauseButton;
    private MaterialButton speedButton;
    private MaterialButton addBookmarkButton;
    private MaterialButton shareButton;
    private TextView bookmarksListText;

    private String currentFileName;
    private List<Integer> bookmarks;
    private float[] speedOptions = {1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
    private int currentSpeedIndex = 0;

    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bookmarks = new ArrayList<>();

        fileNameText = findViewById(R.id.fileNameText);
        currentTimeText = findViewById(R.id.currentTimeText);
        durationText = findViewById(R.id.durationText);
        speedText = findViewById(R.id.speedText);
        seekBar = findViewById(R.id.seekBar);
        playPauseButton = findViewById(R.id.playPauseButton);
        speedButton = findViewById(R.id.speedButton);
        addBookmarkButton = findViewById(R.id.addBookmarkButton);
        shareButton = findViewById(R.id.exportButton);
        bookmarksListText = findViewById(R.id.bookmarksListText);

        handleIncomingIntent(getIntent());
        setupListeners();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri uri = intent.getData();
            if (uri != null) {
                loadAudioFile(uri);
            }
        }
    }

    private void loadAudioFile(Uri uri) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }

            currentFileName = getBaseName(uri);
            fileNameText.setText(currentFileName);

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.prepare();

            seekBar.setMax(mediaPlayer.getDuration());
            durationText.setText(formatTime(mediaPlayer.getDuration()));

            mediaPlayer.setOnCompletionListener(mp -> {
                playPauseButton.setText("Play");
                handler.removeCallbacks(updateSeekBar);
            });

            bookmarks.clear();
            updateBookmarksList();
            playPauseButton.setText("Play");

        } catch (IOException e) {
            Toast.makeText(this, "Error loading file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setupListeners() {
        playPauseButton.setOnClickListener(v -> togglePlayPause());
        speedButton.setOnClickListener(v -> changeSpeed());
        addBookmarkButton.setOnClickListener(v -> addBookmark());
        shareButton.setOnClickListener(v -> shareToKeep());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer.seekTo(progress);
                    currentTimeText.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) {
            Toast.makeText(this, "No file loaded", Toast.LENGTH_SHORT).show();
            return;
        }

        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            handler.removeCallbacks(updateSeekBar);
        } else {
            mediaPlayer.start();
            handler.post(updateSeekBar);
        }
        playPauseButton.setText(mediaPlayer.isPlaying() ? "Pause" : "Play");
    }

    private void changeSpeed() {
        if (mediaPlayer == null) return;

        currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.length;
        float speed = speedOptions[currentSpeedIndex];

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(speed));
        }

        speedText.setText(String.format(Locale.US, "%.2fx", speed));
        speedButton.setText(String.format(Locale.US, "Speed: %.2fx", speed));
    }

    private void addBookmark() {
        if (mediaPlayer == null) {
            Toast.makeText(this, "No file loaded", Toast.LENGTH_SHORT).show();
            return;
        }

        int position = mediaPlayer.getCurrentPosition();
        bookmarks.add(position);
        Collections.sort(bookmarks);
        updateBookmarksList();

        Toast.makeText(this, "Bookmark added: " + formatTime(position), Toast.LENGTH_SHORT).show();
    }

    private void updateBookmarksList() {
        StringBuilder sb = new StringBuilder();
        for (int bookmark : bookmarks) {
            sb.append(formatTime(bookmark)).append("\n");
        }
        bookmarksListText.setText(sb.toString());
    }

    private void shareToKeep() {
        if (currentFileName == null || currentFileName.isEmpty()) {
            Toast.makeText(this, "No file loaded", Toast.LENGTH_SHORT).show();
            return;
        }

        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "No bookmarks to share", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedAccount = prefs.getString(PREF_SELECTED_ACCOUNT, null);

        if (savedAccount != null) {
            sendToKeep(savedAccount);
        } else {
            showAccountPicker();
        }
    }

    private void showAccountPicker() {
        AccountManager accountManager = AccountManager.get(this);
        Account[] accounts = accountManager.getAccountsByType("com.google");

        if (accounts.length == 0) {
            Toast.makeText(this, "No Google accounts found on this device", Toast.LENGTH_LONG).show();
            sendToKeep(null);
            return;
        }

        String[] accountNames = new String[accounts.length];
        for (int i = 0; i < accounts.length; i++) {
            accountNames[i] = accounts[i].name;
        }

        new AlertDialog.Builder(this, R.style.Theme_AudioBookmarkPlayer_Dialog)
                .setTitle("Select Google Account for Keep")
                .setItems(accountNames, (dialog, which) -> {
                    String selectedAccount = accountNames[which];
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putString(PREF_SELECTED_ACCOUNT, selectedAccount)
                            .apply();
                    sendToKeep(selectedAccount);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendToKeep(String account) {
        StringBuilder body = new StringBuilder();
        if (account != null) {
            body.append("Account: ").append(account).append("\n");
        }
        body.append("Label: Edit-times\n\n");
        for (int bookmark : bookmarks) {
            body.append(formatTime(bookmark)).append("\n");
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, currentFileName);
        shareIntent.putExtra(Intent.EXTRA_TEXT, body.toString());

        // Target Google Keep specifically
        shareIntent.setPackage("com.google.android.keep");

        try {
            startActivity(shareIntent);
        } catch (android.content.ActivityNotFoundException e) {
            // Fall back to generic share chooser if Keep is not installed
            shareIntent.setPackage(null);
            startActivity(Intent.createChooser(shareIntent, "Share bookmarks"));
        }
    }

    private Runnable updateSeekBar = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                int currentPosition = mediaPlayer.getCurrentPosition();
                seekBar.setProgress(currentPosition);
                currentTimeText.setText(formatTime(currentPosition));
                handler.postDelayed(this, 100);
            }
        }
    };

    private String formatTime(int milliseconds) {
        int seconds = milliseconds / 1000;
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs);
    }

    private String getBaseName(Uri uri) {
        String path = uri.getLastPathSegment();
        if (path == null) return "Unknown";

        int dotIndex = path.lastIndexOf('.');
        if (dotIndex > 0) {
            return path.substring(0, dotIndex);
        }
        return path;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        handler.removeCallbacks(updateSeekBar);
    }
}
