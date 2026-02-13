package com.audiobookmark.player;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1;
    
    private MediaPlayer mediaPlayer;
    private TextView fileNameText;
    private TextView currentTimeText;
    private TextView durationText;
    private TextView speedText;
    private SeekBar seekBar;
    private Button playPauseButton;
    private Button speedButton;
    private Button addBookmarkButton;
    private Button exportButton;
    private TextView bookmarksListText;
    
    private String currentFileName;
    private String currentFilePath;
    private List<Integer> bookmarks;
    private float[] speedOptions = {1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
    private int currentSpeedIndex = 0;
    
    private Handler handler = new Handler();
    private boolean isPlaying = false;

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
        exportButton = findViewById(R.id.exportButton);
        bookmarksListText = findViewById(R.id.bookmarksListText);

        checkPermissions();
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
            
            currentFilePath = uri.toString();
            currentFileName = getBaseName(uri);
            fileNameText.setText(currentFileName);
            
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.prepare();
            
            seekBar.setMax(mediaPlayer.getDuration());
            durationText.setText(formatTime(mediaPlayer.getDuration()));
            
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    isPlaying = false;
                    playPauseButton.setText("Play");
                    handler.removeCallbacks(updateSeekBar);
                }
            });
            
            bookmarks.clear();
            updateBookmarksList();
            
        } catch (IOException e) {
            Toast.makeText(this, "Error loading file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setupListeners() {
        playPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlayPause();
            }
        });

        speedButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeSpeed();
            }
        });

        addBookmarkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addBookmark();
            }
        });

        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportBookmarks();
            }
        });

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

        if (isPlaying) {
            mediaPlayer.pause();
            playPauseButton.setText("Play");
            handler.removeCallbacks(updateSeekBar);
        } else {
            mediaPlayer.start();
            playPauseButton.setText("Pause");
            handler.post(updateSeekBar);
        }
        isPlaying = !isPlaying;
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

    private void exportBookmarks() {
        if (currentFileName == null || currentFileName.isEmpty()) {
            Toast.makeText(this, "No file loaded", Toast.LENGTH_SHORT).show();
            return;
        }

        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "No bookmarks to export", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            File outputDir = new File(Environment.getExternalStorageDirectory(), "_Edit-times");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            File outputFile = new File(outputDir, currentFileName + ".txt");
            FileWriter writer = new FileWriter(outputFile);
            
            writer.write(currentFileName + "\n");
            for (int bookmark : bookmarks) {
                writer.write(formatTime(bookmark) + "\n");
            }
            
            writer.close();

            Toast.makeText(this, "Exported to " + outputFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Runnable updateSeekBar = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && isPlaying) {
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

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST_CODE);
        }
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
