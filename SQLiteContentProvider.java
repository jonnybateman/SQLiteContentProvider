/*------------------------------------------------------------------------------------------
 |              Class: SQLiteContentProvider.java
 |             Author: Jon Bateman
 |            Version: 1.2.9
 |
 |            Purpose: Content Provider used for interacting with a SQLite database. Targeted
 |                     database name is passed when opening a database connection to create a
 |                     DBHelper instance. This allows us to interact with that database.
 |                     The provider also contains a service which can be bound to through an AIDL
 |                     Interface (Inter Process Communication). This service is used when
 |                     the user starts a database transaction. It guarantees that any database
 |                     operations that occur during an open transaction will be executed
 |                     within the same worker thread. Failure to utilize the same thread during
 |                     an open database transaction would result in a database locked exception.
 |                     Content providers use Binder Threads. At any time the system can change
 |                     which binder thread the provider uses leading to the aforementioned
 |                     exception. The service and its associated worker thread are created and
 |                     destroyed as the user starts and stops database transactions from within
 |                     SQLiteDevStudio.
 |                     An encrypted parameter is passed from SQLiteDevStudio for each database
 |                     operation request. If the decrypted parameter does not match the provider
 |                     access code stored locally the request will be discarded. Provides an
 |                     element of security to prevent malicious apps/software from accessing your
 |                     data via the content provider.
 |
 |                     App specific alterations:-
 |                         1. At line 37 use package name specific to your app.
 |                         2. At line 90 enter name of your provider authority. This should be
 |                            in internet domain ownership format, e.g. com.abc.xyz
 |
 |      Inherits from: ContentProvider.class
 |
 |         Interfaces: N/A
 |
 | Intent/Bundle Args: N/A
 +------------------------------------------------------------------------------------------*/
package <your_package_name>;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.OperationCanceledException;
import android.os.Process;
import android.os.RemoteException;
import android.util.Base64;
import android.util.Log;
import com.cqueltech.sqlitedevstudio.ContentProviderAidlCallback;
import com.cqueltech.sqlitedevstudio.ContentProviderAidlInterface;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class SQLiteContentProvider extends ContentProvider {

    private static final String AUTHORITY = <your_provider_authority>;
    private static final Uri BASE_URI = Uri.parse("content://" + AUTHORITY);
    private static final String SHARED_PREF = "SQLiteDevStudioPref";
    private static SharedPreferences sharedPreferences;
    private static final String INTERNAL_DB_DIRECTORY = "/databases/";

    private static final String PATH_DML_STATEMENT = "dml_statement";
    private static final String PATH_DDL_STATEMENT = "ddl_statement";
    private static final String PATH_SIMPLE_QUERY = "simple_query";
    private static final String PATH_COMPLEX_QUERY = "complex_query";
    private static final String PATH_APPLY_BATCH = "apply_batch";
    private static final String PATH_FK_CONSTRAINT = "fk_constraint";

    private static final int DML_STATEMENT = 2;
    private static final int DDL_STATEMENT = 6;
    private static final int SIMPLE_QUERY = 3;
    private static final int COMPLEX_QUERY = 4;
    private static final int APPLY_BATCH = 5;
    private static final int FK_CONSTRAINT = 7;

    private static final String KEY_URI_PARAMETER_TABLE = "table";
    private static final String KEY_URI_PARAMETER_SQL = "sql";
    private static final String KEY_URI_PARAMETER_LIMIT = "limit";
    private static final String KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE = "access_code";
    private static final String KEY_URI_PARAMETER_FK = "foreign_key";
    private static final String KEY_PREFERENCE_ENCRYPTION_KEY = "key";
    private static final String KEY_PREFERENCE_ACCESS_CODE = "access_code";
    private static final String KEY_BUNDLE_CONNECTION_CHECK = "connection_check";
    private static final String KEY_BUNDLE_DATABASE = "database";

    private static final String PROVIDER_CALL_METHOD_OPEN = "openDatabaseConnection";
    private static final String PROVIDER_CALL_METHOD_CLOSE = "closeDatabaseConnection";
    private static final String PROVIDER_CALL_METHOD_CHECK = "checkDatabaseConnectionExists";

    private static DBHelper dbHelper;
    private static SQLiteDatabase db;

    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        uriMatcher.addURI(AUTHORITY, PATH_DML_STATEMENT, DML_STATEMENT);
        uriMatcher.addURI(AUTHORITY, PATH_DDL_STATEMENT, DDL_STATEMENT);
        uriMatcher.addURI(AUTHORITY, PATH_SIMPLE_QUERY, SIMPLE_QUERY);
        uriMatcher.addURI(AUTHORITY, PATH_COMPLEX_QUERY, COMPLEX_QUERY);
        uriMatcher.addURI(AUTHORITY, PATH_APPLY_BATCH, APPLY_BATCH);
        uriMatcher.addURI(AUTHORITY, PATH_FK_CONSTRAINT, FK_CONSTRAINT);
    }

    @Override
    public boolean onCreate() {

        @SuppressLint("SdCardPath")
        File file = new File("/data/data/" + getContext().getPackageName() + "/shared_prefs/" + SHARED_PREF + ".xml");
        if (!file.exists()) {
            SharedPreferences sharedPreferences = getContext().getSharedPreferences(SHARED_PREF, Context.MODE_PRIVATE);
            SharedPreferences.Editor prefEditor = sharedPreferences.edit();
            prefEditor.putString(KEY_PREFERENCE_ENCRYPTION_KEY, getContext().getString(R.string.provider_encryption_key));
            prefEditor.putString(KEY_PREFERENCE_ACCESS_CODE, getContext().getString(R.string.provider_access_code));
            prefEditor.apply();
        }
        sharedPreferences = getContext().getSharedPreferences(SHARED_PREF, Context.MODE_PRIVATE);
        return true;
    }

    private static class DBHelper extends SQLiteOpenHelper {

        DBHelper(Context context, String dbName, int dbVersion) {
            super(context, dbName, null, dbVersion);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Database already created, do nothing.
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Database not to be upgraded through content provider.
        }

        @Override
        public void onConfigure(SQLiteDatabase db) {
            super.onConfigure(db);
            if (!db.isReadOnly()) {
                db.setForeignKeyConstraintsEnabled(true);
            }
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            if (!db.isWriteAheadLoggingEnabled()) {
                db.enableWriteAheadLogging();
            }
        }
    }

    public static class ContentProviderAidlService extends Service {

        private static final String SQL_TYPE_QUERY = "query";
        private static final String SQL_TYPE_DELETE = "delete";
        private static final String SQL_TYPE_INSERT = "insert";
        private static final String SQL_TYPE_UPDATE = "update";
        private static final String SQL_TYPE_PRAGMA = "pragma";
        private static final String SQL_TYPE_DDL = "ddl";
        private static final String SQL_TYPE_TABLE = "table";
        private static final String SQL_TYPE_VIEW = "view";
        private static final String SQL_TYPE_TRIGGER = "trigger";
        private static final String SQL_TYPE_INDEX = "index";
        private static final String SQL_TYPE_BATCH = "batch";
        private static final String SQL_TYPE_TRANSACTION = "transaction";
        private static final String SQL_TYPE_CLOSE_DATABASE = "closeDatabaseConnection";
        private static final String SQL_TYPE_IN_TRANSACTION = "inTransaction";

        private static CancellationSignal cancellationSignal = new CancellationSignal();
        private static String dbPath;
        private static SQLiteDatabase db;
        private DatabaseOperationsThread databaseOperationsThread;
        private static final String KEY_AIDL_CALLBACK_LIST = "aidl_callback_list";
        private static ContentProviderAidlCallback aidlCallback;

        @Override
        public void onCreate() {
           
            super.onCreate();
            dbPath = getApplicationInfo().dataDir + INTERNAL_DB_DIRECTORY;
            sharedPreferences = getApplicationContext().getSharedPreferences(SHARED_PREF, Context.MODE_PRIVATE);

            if (databaseOperationsThread == null || !databaseOperationsThread.isAlive()) {
                databaseOperationsThread = new DatabaseOperationsThread();
                databaseOperationsThread.start();
            }

            ObservableAidlCallbackList.setOnAidlCallbackListChangedListener(new OnAidlReturnValueChangedListener() {
                @Override
                public void onAidlReturnValueChanged(List<String> list) {
                    if (aidlCallback != null) {
                        try {
                            aidlCallback.aidlInterfaceCallback(list);
                        } catch (RemoteException e) {
                            Log.d("SQLiteContentProvider", "Exception:" + e.toString() + "\n" +
                                    Arrays.toString(Thread.currentThread().getStackTrace()).replace(',', '\n'));
                        }
                    }
                }
            });
        }

        private interface OnAidlReturnValueChangedListener {
            void onAidlReturnValueChanged(List<String> list);
        }

        private static class ObservableAidlCallbackList {
            private static OnAidlReturnValueChangedListener listener;

            static void setOnAidlCallbackListChangedListener(OnAidlReturnValueChangedListener pListener) {
                listener = pListener;
            }

            static void setAidlCallbackList(List<String> aidlCallbackList) {
                if (listener != null)
                    listener.onAidlReturnValueChanged(aidlCallbackList);
            }
        }

        private static class DatabaseOperationsThread extends Thread {
            Handler operationsHandler;
            Looper looper;

            @Override
            public void run() {
                super.run();
                Looper.prepare();
                looper = Looper.myLooper();
             
                operationsHandler = new Handler(looper) {
                    @Override
                    public void handleMessage(Message msg) {
                        Bundle bundle = msg.getData();
                        if (bundle.containsKey(KEY_AIDL_CALLBACK_LIST)) {
                            ObservableAidlCallbackList.setAidlCallbackList(
                                    bundle.getStringArrayList(KEY_AIDL_CALLBACK_LIST));
                        }
                    }
                };
                Looper.loop();
            }

            public void addOperationToQueue(Runnable operation) {
                operationsHandler.post(operation);
            }
        }

        private class DatabaseOperationRunnable implements Runnable {
            private final String sqlType;
            private final String sql;
            private final String dbName;
            private final String accessCode;
            private final ContentProviderAidlCallback callback;
            private final String object;
            private final String[] projection;
            private final String selection;
            private final String[] selectionArgs;
            private final String sortOrder;
            private final String limitStartPosition;
            private final String limitEndPosition;
            private final ContentValues values;
            private final ContentValues[] rows;
            private final boolean displayQueryResults;

            DatabaseOperationRunnable(String sqlType,
                                      String sql,
                                      String dbName,
                                      String accessCode,
                                      ContentProviderAidlCallback callback,
                                      String object,
                                      String[] projection,
                                      String selection,
                                      String[] selectionArgs,
                                      String sortOrder,
                                      String limitStartPosition,
                                      String limitEndPosition,
                                      ContentValues values,
                                      ContentValues[] rows,
                                      boolean displayQueryResults) {
                this.sqlType = sqlType;
                this.sql = sql;
                this.dbName = dbName;
                this.accessCode = accessCode;
                this.callback = callback;
                this.object = object;
                this.projection = projection;
                this.selection = selection;
                this.selectionArgs = selectionArgs;
                this.sortOrder = sortOrder;
                this.limitStartPosition = limitStartPosition;
                this.limitEndPosition = limitEndPosition;
                this.values = values;
                this.rows = rows;
                this.displayQueryResults = displayQueryResults;
            }

            @Override
            public void run() {
                aidlCallback = callback;
                ArrayList<String> list = new ArrayList<>();
                String operationResult = null;
                if (decryptUriAccessParameter(accessCode)) {
                    switch (sqlType) {
                        case SQL_TYPE_TRANSACTION:
                            try {
                                switch (sql) {
                                    case "begin transaction":
                                        if (db == null || !db.isOpen())
                                            db = SQLiteDatabase.openDatabase(dbPath + dbName, null, SQLiteDatabase.OPEN_READWRITE);
                                        db.beginTransaction();
                                        break;
                                    case "commit":
                                        db.setTransactionSuccessful();
                                        db.endTransaction();
                                        break;
                                    case "rollback":
                                        db.endTransaction();
                                        break;
                                }
                                operationResult = "true";
                            } catch (SQLException | IllegalStateException e) {
                                operationResult = "Exception:" + e.toString();
                            }
                            break;

                        case SQL_TYPE_CLOSE_DATABASE:
                            if (db != null && db.isOpen() && db.inTransaction()) {
                                db.endTransaction();
                            }
                            if (db != null && db.isOpen()) {
                                db.close();
                            }
                            break;

                        case SQL_TYPE_IN_TRANSACTION:
                            if (db != null && db.isOpen()) {
                                operationResult = String.valueOf(db.inTransaction());
                            } else {
                                operationResult = "false";
                            }
                            break;

                        case SQL_TYPE_QUERY:
                        case SQL_TYPE_PRAGMA:
                            if (cancellationSignal.isCanceled()) {
                                cancellationSignal = new CancellationSignal();
                            }
                            String limitRows = null;
                            if (limitStartPosition != null) {
                                limitRows = limitStartPosition + "," + limitEndPosition;
                            }
                            List<String> queryRows = new ArrayList<>();
                            Cursor cursor = null;

                            try {
                                if (sql == null) {
                                    cursor = db.query(
                                            false,
                                            object,
                                            projection,
                                            selection,
                                            selectionArgs,
                                            null,
                                            null,
                                            sortOrder,
                                            limitRows,
                                            cancellationSignal);
                                } else {
                                    String tempSql = sql;
                                    if (!sql.toLowerCase().matches(".*\\blimit\\b.*") &&
                                            !sqlType.equalsIgnoreCase(SQL_TYPE_PRAGMA) &&
                                            limitRows != null) {
                                        tempSql = sql + " limit " + limitRows;
                                    }
                                    cursor = db.rawQuery(
                                            tempSql,
                                            null,
                                            cancellationSignal);
                                }

                                if (cursor != null) {
                                    if (cursor.moveToFirst()) {
                                        StringBuilder stringBuilder = new StringBuilder();
                                        for (int i = 0; i < cursor.getColumnCount(); i++) {
                                            stringBuilder.append(cursor.getColumnName(i));
                                            stringBuilder.append((":"));
                                            stringBuilder.append(cursor.getType(i));
                                            if (i < cursor.getColumnCount() - 1) {
                                                stringBuilder.append(",");
                                            }
                                        }
                                        queryRows.add(stringBuilder.toString());

                                        String value;
                                        do {
                                            stringBuilder.setLength(0);
                                            for (int i = 0; i < cursor.getColumnCount(); i++) {
                                                switch (cursor.getType(i)) {
                                                    case Cursor.FIELD_TYPE_NULL:
                                                        value = null;
                                                        break;
                                                    case Cursor.FIELD_TYPE_FLOAT:
                                                        value = String.format(Locale.getDefault(),
                                                                "%.4f", cursor.getFloat(i));
                                                        break;
                                                    case Cursor.FIELD_TYPE_INTEGER:
                                                        value = Integer.toString(cursor.getInt(i));
                                                        break;
                                                    case Cursor.FIELD_TYPE_STRING:
                                                        //noinspection DuplicateBranchesInSwitch
                                                        value = cursor.getString(i);
                                                        break;
                                                    case Cursor.FIELD_TYPE_BLOB:
                                                        value = "blob";
                                                        break;
                                                    default:
                                                        value = cursor.getString(i);
                                                        break;
                                                }

                                                if (value != null) {
                                                    stringBuilder.append(value);
                                                }
                                                if (i < cursor.getColumnCount() - 1) {
                                                    stringBuilder.append(",");
                                                }
                                            }
                                            queryRows.add(stringBuilder.toString());
                                        } while (cursor.moveToNext());

                                        list.addAll(queryRows);
                                    }
                                }
                            } catch (SQLException | OperationCanceledException e) {
                                if (e instanceof SQLException) {
                                    operationResult = "Exception:" + e.toString();
                                }
                            } finally {
                                if (cursor != null)
                                    cursor.close();
                            }
                            break;

                        case SQL_TYPE_INSERT:
                            int rowCount = 0;
                            cursor = null;
                            try {
                                if (sql == null) {
                                    db.insert(object, null, values);
                                } else {
                                    db.execSQL(sql);
                                }
                                cursor = db.rawQuery("select changes()", null);
                                if (cursor != null) {
                                    if (cursor.moveToFirst())
                                        rowCount = cursor.getInt(0);
                                    cursor.close();
                                }
                                operationResult = String.valueOf(rowCount);
                            } catch (SQLException e) {
                                operationResult = "Exception:" + e.toString();
                            } finally {
                                if (cursor != null)
                                    cursor.close();
                            }
                            break;

                        case SQL_TYPE_UPDATE:
                        case SQL_TYPE_DELETE:
                            rowCount = 0;
                            cursor = null;
                            try {
                                if (sql == null) {
                                    if (sqlType.equalsIgnoreCase(SQL_TYPE_UPDATE))
                                        rowCount = db.update(
                                                object,
                                                values,
                                                selection,
                                                selectionArgs);
                                    else
                                        rowCount = db.delete(object, selection, selectionArgs);
                                } else {
                                    db.execSQL(sql);
                                    cursor = db.rawQuery("select changes()", null);
                                    if (cursor != null) {
                                        if (cursor.moveToFirst())
                                            rowCount = cursor.getInt(0);
                                        cursor.close();
                                    }
                                }
                                operationResult = String.valueOf(rowCount);
                            } catch (SQLException e) {
                                operationResult = "Exception:" + e.toString();
                            } finally {
                                if (cursor != null)
                                    cursor.close();
                            }
                            break;

                        case SQL_TYPE_DDL:
                        case SQL_TYPE_TABLE:
                        case SQL_TYPE_TRIGGER:
                        case SQL_TYPE_INDEX:
                        case SQL_TYPE_VIEW:
                            try {
                                db.execSQL(sql);
                                operationResult = "true";
                            } catch (SQLException e) {
                                operationResult = "Exception:" + e.toString();
                            }
                            break;

                        case SQL_TYPE_BATCH:
                            int index = 0;
                            try {
                                for (ContentValues contentValues : rows) {
                                    db.insert(object, null, contentValues);
                                    index++;
                                }
                                operationResult = String.valueOf(index);
                            } catch (SQLException e) {
                                operationResult = "Exception:" + e.toString() + "(row " + index + ")";
                            }
                            break;
                    }
                } else {
                    operationResult = "Exception: Could not decrypt access code";
                }

                list.add(0, operationResult);
                list.add(1, sql);
                list.add(2, sqlType);
                list.add(3, object);
                list.add(4, String.valueOf(displayQueryResults));
                Message msg = databaseOperationsThread.operationsHandler.obtainMessage();
                Bundle bundle = new Bundle();
                bundle.putStringArrayList(KEY_AIDL_CALLBACK_LIST, list);
                msg.setData(bundle);
                msg.setTarget(databaseOperationsThread.operationsHandler);
                msg.sendToTarget();
            }
        }

        final ContentProviderAidlInterface.Stub aidlCall = new ContentProviderAidlInterface.Stub() {

            @Override
            public void executeDatabaseOperation(
                    String sqlType,
                    String sql,
                    String dbName,
                    String accessCode,
                    ContentProviderAidlCallback callback,
                    String object,
                    String[] projection,
                    String selection,
                    String[] selectionArgs,
                    String sortOrder,
                    String limitStartPosition,
                    String limitEndPosition,
                    ContentValues values,
                    ContentValues[] rows,
                    boolean displayQueryResults) {
                databaseOperationsThread.addOperationToQueue(new DatabaseOperationRunnable(
                        sqlType,
                        sql,
                        dbName,
                        accessCode,
                        callback,
                        object,
                        projection,
                        selection,
                        selectionArgs,
                        sortOrder,
                        limitStartPosition,
                        limitEndPosition,
                        values,
                        rows,
                        displayQueryResults));
            }

            @Override
            public void cancelQuery() {
                cancellationSignal.cancel();
            }

            @Override
            public int getPid() {
                return Process.myPid();
            }
        };

        @Override
        public IBinder onBind(Intent intent) {
            return aidlCall;
        }

        @Override
        public void onDestroy() {

            if (db != null && db.isOpen()) {
                db.close();
            }
            if (databaseOperationsThread != null && databaseOperationsThread.isAlive()) {
                databaseOperationsThread.interrupt();
                databaseOperationsThread = null;
            }
            super.onDestroy();
        }
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder, CancellationSignal cancelSignal) {

        Cursor cursor = null;

        if (decryptUriAccessParameter(uri.getQueryParameter(KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE))) {
            if (dbHelper != null) {
                String table = uri.getQueryParameter(KEY_URI_PARAMETER_TABLE);
                String sql = uri.getQueryParameter(KEY_URI_PARAMETER_SQL);
                String rowsLimit = uri.getQueryParameter(KEY_URI_PARAMETER_LIMIT);

                if (sql != null) {
                    if (sortOrder != null) {
                        sql = sql.concat(" order by " + sortOrder);
                    }
                    if (rowsLimit != null) {
                        sql = sql.concat(" LIMIT " + rowsLimit);
                    }
                }

                switch (uriMatcher.match(uri)) {
                    case SIMPLE_QUERY:
                        cursor = db.query(
                                false,
                                table,
                                projection,
                                selection,
                                selectionArgs,
                                null,
                                null,
                                sortOrder,
                                rowsLimit,
                                cancelSignal);
                        break;

                    case DML_STATEMENT:
                    case DDL_STATEMENT:
                        db.execSQL(sql);
                        break;

                    case COMPLEX_QUERY:
                        if (sql != null) {
                            cursor = db.rawQuery(sql, selectionArgs, cancelSignal);
                        }
                        break;

                    case FK_CONSTRAINT:
                        String toggleFkConstraint = uri.getQueryParameter(KEY_URI_PARAMETER_FK);
                        if (toggleFkConstraint != null) {
                            db.setForeignKeyConstraintsEnabled(Boolean.parseBoolean(toggleFkConstraint));
                        }
                        break;

                    default:
                        throw new UnsupportedOperationException("Unknown URI: " + uri);
                }
            }
        } else {
            Log.d("SQLiteContentProvider", "Access Code not valid");
        }

        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues contentValues) {

        String table = uri.getQueryParameter(KEY_URI_PARAMETER_TABLE);
        Uri returnUri = BASE_URI;
        long id = -1L;
        int rowsInserted = 0;

        if (decryptUriAccessParameter(uri.getQueryParameter(KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE))) {

            if (dbHelper != null) {
                String sql = uri.getQueryParameter(KEY_URI_PARAMETER_SQL);

                try {
                    db.beginTransaction();
                    if (sql != null) {
                        db.execSQL(sql);
                        id = 0L;
                    } else {
                        id = db.insertOrThrow(table, null, contentValues);
                    }

                    Cursor cursor = db.rawQuery("select changes()", null);
                    if (cursor != null) {
                        if (cursor.moveToFirst())
                            rowsInserted = cursor.getInt(0);
                        cursor.close();
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
        }
        returnUri = Uri.parse(returnUri.toString() + "?rows_inserted=" + rowsInserted);
        return ContentUris.withAppendedId(returnUri, id);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {

        int rowsDeleted = 0;

        if (decryptUriAccessParameter(uri.getQueryParameter(KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE))) {

            if (dbHelper != null) {

                String table = uri.getQueryParameter(KEY_URI_PARAMETER_TABLE);
                String sql = uri.getQueryParameter(KEY_URI_PARAMETER_SQL);

                if (sql != null) {
                    try {
                        db.beginTransaction();
                        db.execSQL(sql);
                        Cursor cursor = db.rawQuery("select changes()", null);
                        if (cursor != null) {
                            if (cursor.moveToFirst())
                                rowsDeleted = cursor.getInt(0);
                            cursor.close();
                        }
                        db.setTransactionSuccessful();
                    } finally {
                        db.endTransaction();
                    }

                } else {
                    rowsDeleted = db.delete(table, selection, selectionArgs);
                }
            }
        }

        return rowsDeleted;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {

        int rowsUpdated = 0;

        if (decryptUriAccessParameter(uri.getQueryParameter(KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE))) {
            if (dbHelper != null) {
                String sql = uri.getQueryParameter(KEY_URI_PARAMETER_SQL);

                if (sql != null) {
                    try {
                        db.beginTransaction();
                        db.execSQL(sql);
                        Cursor cursor = db.rawQuery("select changes()", null);
                        if (cursor != null) {
                            if (cursor.moveToFirst())
                                rowsUpdated = cursor.getInt(0);
                            cursor.close();
                        }
                        db.setTransactionSuccessful();
                    } finally {
                        db.endTransaction();
                    }
                } else {
                    String table = uri.getQueryParameter(KEY_URI_PARAMETER_TABLE);
                    rowsUpdated = db.update(table, values, selection, selectionArgs);
                }
            }
        }

        return rowsUpdated;
    }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations) {

        ContentProviderResult[] results = null;

        if (decryptUriAccessParameter(operations.get(0).getUri().getQueryParameter(KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE))) {
            results = new ContentProviderResult[operations.size()];

            if (dbHelper != null) {
                try {
                    results = super.applyBatch(operations);
                } catch (OperationApplicationException oae) {
                    Log.d("SQLiteContentProvider", "Exception:" + oae.toString() + "\n" +
                            Arrays.toString(Thread.currentThread().getStackTrace()).replace(',', '\n'));
                }
            }
        }

        return results;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {

        switch (method) {
            case PROVIDER_CALL_METHOD_OPEN:
                if (db != null && db.isOpen()) {
                    db.close();
                }
                if (dbHelper != null) {
                    dbHelper.close();
                }

                createDBHelperInstance(extras.getString(KEY_BUNDLE_DATABASE));

                if (dbHelper != null) {
                    db = dbHelper.getWritableDatabase();
                }
                Bundle bundle = new Bundle();
                bundle.putString(KEY_BUNDLE_CONNECTION_CHECK, String.valueOf(db != null && db.isOpen()));
                return bundle;

            case PROVIDER_CALL_METHOD_CLOSE:
                if (db != null && db.isOpen()) {
                    db.close();
                }
                if (dbHelper != null) {
                    dbHelper.close();
                }
                break;

            case PROVIDER_CALL_METHOD_CHECK:
                bundle = new Bundle();
                bundle.putString(KEY_BUNDLE_CONNECTION_CHECK, String.valueOf(db != null && db.isOpen()));
                return bundle;
        }

        return null;
    }
 
    private void createDBHelperInstance(String dbName) {

        try {
            String dbPath = Objects.requireNonNull(getContext()).getApplicationInfo().dataDir + "/databases/";
            File fileDir = new File(dbPath);
            File[] files = fileDir.listFiles();

            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        if (file.getName().equals(dbName)) {
                            SQLiteDatabase sqliteDatabase = SQLiteDatabase.openDatabase(dbPath + file.getName(),
                                    null, SQLiteDatabase.OPEN_READONLY);
                            int dbVersion = sqliteDatabase.getVersion();
                            sqliteDatabase.close();

                            dbHelper = new DBHelper(getContext(), file.getName(), dbVersion);
                            db = dbHelper.getWritableDatabase();
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d("SQLiteContentProvider", "Exception:" + e.toString() + "\n" +
                    Arrays.toString(Thread.currentThread().getStackTrace()).replace(',', '\n'));
        }
    }

    private static boolean decryptUriAccessParameter(String encodedEncryptedParameterString) {

        boolean accessAllowed = false;

        try {
            byte[] decodedEncryptedParameterByte = Base64.decode(encodedEncryptedParameterString, Base64.URL_SAFE);
            SecretKeySpec sKeySpec = new SecretKeySpec(sharedPreferences.getString(KEY_PREFERENCE_ENCRYPTION_KEY, "").getBytes(), "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, sKeySpec, new GCMParameterSpec(
                    128, decodedEncryptedParameterByte, 0, 12));
            byte[] decodedDecryptedParameterByte = cipher.doFinal(
                    decodedEncryptedParameterByte, 12, decodedEncryptedParameterByte.length - 12);

            if (new String(decodedDecryptedParameterByte, StandardCharsets.UTF_8)
                    .equals(sharedPreferences.getString(KEY_PREFERENCE_ACCESS_CODE, ""))) {
                accessAllowed = true;
            }

            try {
                Method method = SecretKeySpec.class.getMethod("destroy");
                method.invoke(sKeySpec);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                // ignore
            }

            Arrays.fill(decodedDecryptedParameterByte, 0, decodedDecryptedParameterByte.length - 1, (byte) 0);
            //noinspection UnusedAssignment
            decodedDecryptedParameterByte = null;

        } catch (NoSuchAlgorithmException |
                NoSuchPaddingException |
                InvalidKeyException |
                InvalidAlgorithmParameterException |
                BadPaddingException |
                IllegalBlockSizeException e) {
            throw new SecurityException(e);
        }

        return accessAllowed;
    }
}
