# SQLiteContentProvider

SQLiteContentProvider.java manages access to an App's central repository of data, in this case a SQLite database. The primary use of a Content Provider is to allow data access from one application to another application. It is essentially a conduit for manipulating a database from a different application to the one that is currently in use.

SQLiteContentProvider.java is a customized Content Provider intended for use by the SQLiteManager App which can be downloaded from the GooglePlay Library. SQLiteManager is a SQLite Database management and development tool. It can be used to view, maintain and build SQLite databases as well as manipulate data.

## Installation

Installation into your own app has two simple steps:

1. Download the script SQLiteContentProvider.java and include the class in your own project. Prior to compiling the .java script replace the final string variable 'AUTHORITY' with your own internet domain as the basis of your provider authority. For example, 'private static final String AUTHORITY = "com.abc.xyz";'. This is used to identify which content provider is being targeted.

2. Your app needs to be made aware of the content provider, include the following provider tag in the Manifest file:
      
          ...
              </activity>
              <provider
                  android:authorities="com.abc.xyz"
                  android:name="SQLiteContentProvider"
                  android:grantUriPermissions="true"
                  android:exported="false"
                  android:protectionLevel="signature"
                  android:syncable="true" />
              ...
          </application>
          ...
          
## Contributing

Pull Requests are welcome. For major changes, please open an issue first to discuss what you would like to change.
