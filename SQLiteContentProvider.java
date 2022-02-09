/*------------------------------------------------------------------------------------------
 |              Class: SQLiteContentProvider.java
 |             Author: Jon Bateman
 |            Version: 1.2.4
 |
 |            Purpose: Content Provider used for interacting with a SQLite database. Targeted
 |                     database name is passed as a URI parameter so that the relevant DBHelper
 |                     instance can be used to interact with that database. Using this method
 |                     the same content provider can be utilized for multiple databases within
 |                     the same app.
 |                     An encrypted Uri parameter is passed from the calling application. If the
 |                     decrypted parameter does not match the provider access code the provider
 |                     request will be discarded.
 |
 |                     App specific alterations:-
 |                         1. At line 26 use package name specific to your app.
 |                         2. At line 65 enter name of your provider authority. This should be
 |                            in internet domain ownership format, e.g. com.abc.xyz
 |
 |      Inherits from: ContentProvider.class
 |
 |         Interfaces: N/A
 |
 | Intent/Bundle Args: N/A
 +------------------------------------------------------------------------------------------*/
package <package_name>;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.util.Base64;
import android.util.Log;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class SQLiteContentProvider extends ContentProvider {

    private static final String AUTHORITY = "<provider.authority.name>";
    private static final Uri BASE_URI = Uri.parse("content://" + AUTHORITY);

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

    private static final String KEY_URI_PARAMETER_DATABASE = "database";
    private static final String KEY_URI_PARAMETER_TABLE = "table";
    private static final String KEY_URI_PARAMETER_SQL = "sql";
    private static final String KEY_URI_PARAMETER_LIMIT = "limit";
    private static final String KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE = "access_code";
    private static final String KEY_URI_PARAMETER_FK = "foreign_key";

    private static final String KEY_CURSOR_RESULT = "result";

    private DBHelper dbHelper;

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

            String dbName = uri.getQueryParameter(KEY_URI_PARAMETER_DATABASE);

            if (dbHelper == null || !dbHelper.getDatabaseName().equals(dbName)) {
                createDBHelperInstance(uri);
            }

            if (dbHelper != null) {
                final SQLiteDatabase db = dbHelper.getWritableDatabase();

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
                } else {
                    if (rowsLimit != null) {
                        sortOrder = sortOrder + " LIMIT " + rowsLimit;
                    }
                }

                switch (uriMatcher.match(uri)) {

                    case SIMPLE_QUERY:
                        cursor = db.query(
                                table,
                                projection,
                                selection,
                                selectionArgs,
                                null,
                                null,
                                sortOrder);

                        break;

                    case DML_STATEMENT:
                    case DDL_STATEMENT:
                        cursor = db.rawQuery("select 1 from sqlite_master limit 1", null);

                        Bundle returnBundle = new Bundle();

                        db.execSQL(sql);
                        returnBundle.putInt(KEY_CURSOR_RESULT, 0);

                        if (cursor != null) {
                            cursor.setExtras(returnBundle);
                        }

                        break;

                    case COMPLEX_QUERY:
                        if (sql != null) {
                            cursor = db.rawQuery(sql, selectionArgs, cancelSignal);
                        }

                        break;

                    case FK_CONSTRAINT:
                        cursor = db.rawQuery("select 1 from sqlite_master limit 1", null);

                        returnBundle = new Bundle();

                        String toggleFkConstraint = uri.getQueryParameter(KEY_URI_PARAMETER_FK);
                        if (toggleFkConstraint != null)
                            db.setForeignKeyConstraintsEnabled(Boolean.parseBoolean(toggleFkConstraint));

                        returnBundle.putInt(KEY_CURSOR_RESULT, 0);

                        if (cursor != null)
                            cursor.setExtras(returnBundle);

                        break;

                    default:
                        throw new UnsupportedOperationException("Unknown URI: " + uri);
                }
            }

        } else {
            Log.d("SQLiteContentProvider","Access Code not valid");
        }

        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues contentValues) {

        if (decryptUriAccessParameter(uri.getQueryParameter(KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE))) {

            Uri returnUri = null;
            long id = 0L;

            if (dbHelper != null) {
                final SQLiteDatabase db = dbHelper.getWritableDatabase();

                String table = uri.getQueryParameter(KEY_URI_PARAMETER_TABLE);

                id = db.insertOrThrow(table, null, contentValues);

                returnUri = BASE_URI.buildUpon().appendPath(table).build();
            }

            return ContentUris.withAppendedId(returnUri, id);

        } else {
            return null;
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {

        int rowsDeleted = 0;

        if (decryptUriAccessParameter(uri.getQueryParameter(KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE))) {

            if (dbHelper != null) {

                final SQLiteDatabase db = dbHelper.getWritableDatabase();

                String table = uri.getQueryParameter(KEY_URI_PARAMETER_TABLE);

                rowsDeleted = db.delete(table, selection, selectionArgs);
            }
        }

        return rowsDeleted;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {

        int rowsUpdated = 0;

        if (decryptUriAccessParameter(uri.getQueryParameter(KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE))) {

            if (dbHelper != null) {
                final SQLiteDatabase db = dbHelper.getWritableDatabase();

                String table = uri.getQueryParameter(KEY_URI_PARAMETER_TABLE);

                rowsUpdated = db.update(table, values, selection, selectionArgs);
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
                SQLiteDatabase db = dbHelper.getWritableDatabase();

                db.beginTransaction();
                try {
                    results = super.applyBatch(operations);

                    db.setTransactionSuccessful();

                } catch (OperationApplicationException oae) {
                    Log.d("ContentProvider", "Exception:" + oae.toString());
                } finally {
                    db.endTransaction();
                }
            }
        }

        return results;
    }

    private void createDBHelperInstance(Uri uri) {

        String dbName = uri.getQueryParameter(KEY_URI_PARAMETER_DATABASE);

        String dbPath = Objects.requireNonNull(getContext()).getApplicationInfo().dataDir + "/databases/";
        File fileDir = new File(dbPath);
        File[] files = fileDir.listFiles();
        String match = ".*journal.*|.*-wal.*|.*-shm.*";
        Pattern pattern = Pattern.compile(match);

        if (files != null) {

            for (File file : files) {

                if (file.isFile()) {
                    Matcher fileMatcher = pattern.matcher(file.getName().toLowerCase());

                    if (!fileMatcher.find() ) {

                        if (file.getName().equals(dbName)) {
                            SQLiteDatabase db = SQLiteDatabase.openDatabase(dbPath + file.getName(),
                                    null, SQLiteDatabase.OPEN_READONLY);

                            dbHelper = new DBHelper(getContext(), file.getName(), db.getVersion());

                            db.close();
                        }
                    }
                }
            }
        }
    }

    private boolean decryptUriAccessParameter(String encodedEncryptedParameterString) {

        boolean accessAllowed = false;

        try {
            byte[] decodedEncryptedParameterByte = Base64.decode(encodedEncryptedParameterString, Base64.URL_SAFE);

            SecretKeySpec sKeySpec = new SecretKeySpec(Objects.requireNonNull(
                    getContext()).getResources().getString(R.string.provider_encryption_key).getBytes()
                    , "AES");

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, sKeySpec, new GCMParameterSpec(
                    128, decodedEncryptedParameterByte, 0, 12));

            byte[] decodedDecryptedParameterByte = cipher.doFinal(
                    decodedEncryptedParameterByte, 12, decodedEncryptedParameterByte.length - 12);

            if (new String(decodedDecryptedParameterByte, StandardCharsets.UTF_8)
                    .equals(getContext().getResources().getString(R.string.provider_access_code))) {
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
