/*------------------------------------------------------------------------------------------
 |              Class: SQLiteContentProvider.java
 |             Author: Jon Bateman
 |            Version: 1.1.0
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
package <your.package.name>;

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
import android.util.Base64;
import android.util.Log;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.DestroyFailedException;

public class SQLiteContentProvider extends ContentProvider {

    // This is the symbolic name of your provider. To avoid conflicts with other providers, you
    // should use internet domain ownership as the basis of your provider authority.
    private static final String AUTHORITY = "<enter provider authority here>";
    // The content authority is used to create the base of all URIs which apps will use to contact
    // this content provider.
    static final Uri BASE_URI = Uri.parse("content://" + AUTHORITY);

    private static final String PATH_DML_STATEMENT = "dml_statement";
    private static final String PATH_DDL_STATEMENT = "ddl_statement";
    private static final String PATH_SIMPLE_QUERY = "simple_query";
    private static final String PATH_COMPLEX_QUERY = "complex_query";
    private static final String PATH_APPLY_BATCH = "apply_batch";

    // Use an int for each URI we will run, this represents the different database actions.
    private static final int DML_STATEMENT = 2;
    private static final int DDL_STATEMENT = 6;
    private static final int SIMPLE_QUERY = 3;
    private static final int COMPLEX_QUERY = 4;
    private static final int APPLY_BATCH = 5;

    // Key constants used to identify parameters within the URI.
    private static final String KEY_URI_PARAMETER_DATABASE = "database";
    private static final String KEY_URI_PARAMETER_TABLE = "table";
    private static final String KEY_URI_PARAMETER_SQL = "sql";
    private static final String KEY_URI_PARAMETER_LIMIT = "limit";
    private static final String KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE = "access_code";

    // Key constant used to identify/tag the result of a non-query action when assigning it to a Bundle.
    private static final String KEY_CURSOR_RESULT = "result";

    // HashMap used to store DBHelper instances, one for each database. Each instance identified by
    // it's database name.
    private HashMap<String, DBHelper> dbHelperMap;

    // A URIMatcher that will take in a URI and match it to the appropriate integer identifier above.
    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        uriMatcher.addURI(AUTHORITY, PATH_DML_STATEMENT, DML_STATEMENT);
        uriMatcher.addURI(AUTHORITY, PATH_DDL_STATEMENT, DDL_STATEMENT);
        uriMatcher.addURI(AUTHORITY, PATH_SIMPLE_QUERY, SIMPLE_QUERY);
        uriMatcher.addURI(AUTHORITY, PATH_COMPLEX_QUERY, COMPLEX_QUERY);
        uriMatcher.addURI(AUTHORITY, PATH_APPLY_BATCH, APPLY_BATCH);
    }

    @Override
    @SuppressWarnings({"ConstantConditions"})
    public boolean onCreate() {

        // Create instance of the DBHelper class for each database that exists in the app's directory structure.
        dbHelperMap = new HashMap<>();

        String dbPath = getContext().getApplicationInfo().dataDir + "/databases/";
        File fileDir = new File(dbPath);
        File[] files = fileDir.listFiles();
        String match = ".*journal.*|.*-wal.*|.*-shm.*";
        Pattern pattern = Pattern.compile(match);

        if (files != null) {

            for (File file : files) {

                if (file.isFile()) {
                    Matcher fileMatcher = pattern.matcher(file.getName().toLowerCase());
                    
                    if (!fileMatcher.find()) {
                        SQLiteDatabase db = SQLiteDatabase.openDatabase(dbPath + file.getName(),
                                null, SQLiteDatabase.OPEN_READONLY);

                        dbHelperMap.put(file.getName(), new DBHelper(getContext(), file.getName(), db.getVersion()));
                     
                        if (db != null)
                            db.close();
                    }
                }
            }
        }

        return true;
    }

    // Create a SQLiteOpenHelper class instance which is used to access the targeted database.
    private static class DBHelper extends SQLiteOpenHelper {

        // Constructor
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
    }

    // The getType() method is used to find the MIME type of the results, either a directory of
    // multiple records, or an individual record.
    @Override
    public String getType(Uri uri) {
        return null;
    }

    // Parameters:
    //  uri: The URI to identify the database to be targetted as well as the action to be taken against it.
    //  projection: A string array of columns that will be returned in the result set.
    //  selection: A string defining the criteria for results to be returned.
    //  selectionArgs: Arguments to the above criteria that rows will be checked against.
    //  sortOrder: A string of the column(s) and order to sort the results.
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {

        Cursor cursor = null;
     
        // Determine if the incoming URI request is from SQLiteDevStudio.
        if (decryptUriAccessParameter(uri.getQueryParameter(KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE))) {

            // Select the appropriate DBHelper instance for the targeted database.
            DBHelper dbHelper = getDBHelperInstance(uri);
         
            if (dbHelper != null) {
                final SQLiteDatabase db = dbHelper.getWritableDatabase();

                // Get any parameters that may be attached to the URI.
                String table = uri.getQueryParameter(KEY_URI_PARAMETER_TABLE);
                String sql = uri.getQueryParameter(KEY_URI_PARAMETER_SQL);
                String rowsLimit = uri.getQueryParameter(KEY_URI_PARAMETER_LIMIT);

                if (sql != null) {
                    // Append sort order to SQL string if has been supplied.
                    if (sortOrder != null) {
                        sql = sql.concat(" order by " + sortOrder);
                    }

                    // Append row limit to SQL string if it has been supplied.
                    if (rowsLimit != null) {
                        sql = sql.concat(" LIMIT " + rowsLimit);
                    }
                } else {
                    if (rowsLimit != null) {
                        // No SQL string so apply row limit to sortOrder parameter.
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
                        // Initialise cursor. Doing it this way allows for a Bundle to be returned with the cursor
                        // for a SQL statement that does not retrieve a data set.
                        cursor = db.rawQuery("select 1 from sqlite_master limit 1", null);
              
                        // Create a bundle to store the result of a non query database action.
                        Bundle returnBundle = new Bundle();

                        db.execSQL(sql);
                        returnBundle.putInt(KEY_CURSOR_RESULT, 0);
              
                        if (cursor != null) {
                            // Assign bundle to cursor to pass back result.
                            cursor.setExtras(returnBundle);
                        }

                        break;

                    case COMPLEX_QUERY:
                        if (sql != null) {
                            cursor = db.rawQuery(sql, selectionArgs);
                        }
                        break;

                    default:
                        throw new UnsupportedOperationException("Unknown URI: " + uri);
                }
            }
        }
     
        return cursor;
    }

    // The insert method takes in a ContentValues object, which is a key value pair of column names
    // and values to be inserted.
    @Override
    public Uri insert(Uri uri, ContentValues contentValues) {

        // Determine if the incoming URI request is from SQLiteDevStudio.
        if (decryptUriAccessParameter(uri.getQueryParameter(KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE))) {
            // Select the applicable DBHelper instance for the target database.
            DBHelper dbHelper = getDBHelperInstance(uri);

            Uri returnUri = null;
            long id = 0L;

            if (dbHelper != null) {
                // Open the database readying it for DML/DDL operations.
                final SQLiteDatabase db = dbHelper.getWritableDatabase();

                // Get the table that is to be inserted into from the URI.
                String table = uri.getQueryParameter(KEY_URI_PARAMETER_TABLE);

                // -1 error, unable to insert record; >0 record successfully inserted returning id of new record.
                // Any exception raised will be thrown to calling app.
                id = db.insertOrThrow(table, null, contentValues);

                // Create return URI for returning id to calling application.
                returnUri = BASE_URI.buildUpon().appendPath(table).build();
            }

            // Add id to the returned URI
            return ContentUris.withAppendedId(returnUri, id);
         
        } else {
            return null;
        }
    }

    // The update and delete methods take in a selection string and arguments to define which rows
    // should be updated or deleted. Update method requires a ContentProvider object as well, for
    // columns that will be updated.
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {

        int rowsDeleted = 0;
     
        // Determine if the incoming URI request is from SQLiteDevStudio.
        if (decryptUriAccessParameter(uri.getQueryParameter(KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE))) {

            // Select the applicable DBHelper instance for the target database.
            DBHelper dbHelper = getDBHelperInstance(uri);

            if (dbHelper != null) {

                // Open the database readying it for DML/DDL operations.
                final SQLiteDatabase db = dbHelper.getWritableDatabase();

                // Get the table from which the record is to be deleted.
                String table = uri.getQueryParameter(KEY_URI_PARAMETER_TABLE);

                rowsDeleted = db.delete(table, selection, selectionArgs); // returns number of rows deleted.
            }
        }

        return rowsDeleted;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {

        int rowsUpdated = 0;
     
        // Determine if the incoming URI request is from SQLiteDevStudio.
        if (decryptUriAccessParameter(uri.getQueryParameter(KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE))) {

            // Select the applicable DBHelper instance for the target database.
            DBHelper dbHelper = getDBHelperInstance(uri);

            if (dbHelper != null) {

                // Open the database readying it for DML/DDL operations.
                final SQLiteDatabase db = dbHelper.getWritableDatabase();

                // Get the table to be updated from the URI.
                String table = uri.getQueryParameter(KEY_URI_PARAMETER_TABLE);

                // Any exception will be raised to the calling app.
                rowsUpdated = db.update(table, values, selection, selectionArgs); // return number of rows updated.
            }
        }

        return rowsUpdated;
    }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations) {
     
        ContentProviderResult[] results = null;

        // Determine if the incoming URI request is from SQLiteDevStudio.
        if (decryptUriAccessParameter(operations.get(0).getUri().getQueryParameter(KEY_URI_PARAMETER_PROVIDER_ACCESS_CODE))) {
            results = new ContentProviderResult[operations.size()];

            // Select the DBHelper instance for the targeted database.
            DBHelper dbHelper = getDBHelperInstance(operations.get(0).getUri());

            if (dbHelper != null) {

                // Open database for writing to.
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

        //noinspection ConstantConditions
        return results;
    }

    /*
     * Get the DBHelper instance for the targeted database
     */
    public DBHelper getDBHelperInstance(Uri uri) {

        // Get the database name parameter from the uri.
        String dbName = uri.getQueryParameter(KEY_URI_PARAMETER_DATABASE);
        // Select the applicable DBHelper instance for the target database.
        return dbHelperMap.get(dbName);
    }
 
    /*
     * A decrypt method that decrypts a passed uri parameter. If the decrypted value is valid
     * then permit access to provider.
     */
    private boolean decryptUriAccessParameter(String encodedEncryptedParameterString) {

        boolean accessAllowed = false;

        try {

            // Decode the encrypted access parameter string to reveal the true encrypted string and convert
            // it to a byte array variable.
            byte[] decodedEncryptedParameterByte = Base64.decode(encodedEncryptedParameterString, Base64.URL_SAFE);

            // Create a key spec from the provided encryption key, used to decrypt the provider access parameter.
            SecretKeySpec sKeySpec = new SecretKeySpec(
                    getContext().getResources().getString(R.string.provider_encryption_key).getBytes()
                    , "AES");

            // Initialise the Cipher for decrypting.
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, sKeySpec, new GCMParameterSpec(
                    128, decodedEncryptedParameterByte, 0, 12));

            // Use the Cipher to decrypt the provider access code.
            byte[] decodedDecryptedParameterByte = cipher.doFinal(
                    decodedEncryptedParameterByte, 12, decodedEncryptedParameterByte.length - 12);

            // If the decrypted provider access code matches then allow access to provider methods.
            if (new String(decodedDecryptedParameterByte, StandardCharsets.UTF_8)
                    .equals(getContext().getResources().getString(R.string.provider_access_code))) {
                accessAllowed = true;
            }

            // Ensure sensitive information does not persist.
            try {
                sKeySpec.destroy();
            } catch (DestroyFailedException dfe) {
                Log.d("DecryptUriAccessParam..", dfe.toString());
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

            // Wrap the caught exception in a SecurityException. This will allow the exception to be
            // thrown to the calling application for handling.
            throw new SecurityException(e);
        }

        return accessAllowed;
    } 
}
