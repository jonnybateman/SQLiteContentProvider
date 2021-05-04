# SQLiteContentProvider

SQLiteContentProvider.java manages access to an App's central repository of data, in this case a SQLite database. The primary use of a Content Provider is to allow data access from one application to another application. It is essentially a conduit for manipulating a database from a different application to the one that is currently in use.

SQLiteContentProvider.java is a customized Content Provider intended for use by the SQLite DEV Studio App which can be downloaded from the GooglePlay Library. SQLite Dev Studio is a SQLite Database management and development tool. It can be used to view, maintain and build SQLite databases as well as manipulate data.

## Installation

Installation into your own app has a few simple steps:

1. Download the script SQLiteContentProvider.java and include the class in your own project. Prior to compiling the .java script replace the final string variable 'AUTHORITY' value with your own value (in internet domain ownership format) as the basis of your provider authority. For example, 'private static final String AUTHORITY = "com.abc.xyz";'. This is used to identify which content provider is being targeted.

2. Your app needs to be made aware of the content provider, include the following provider tag in the Manifest file:
      
          ...
              </activity>
              <provider
                  android:authorities="com.abc.xyz"
                  android:name=".SQLiteContentProvider"
                  android:grantUriPermissions="true"
                  android:exported="true"
                  android:protectionLevel="signature"
                  android:syncable="true" />
              ...
          </application>
          ...

3. In Android R and higher we need to implement an intent filter in the manifest due to new Package Visibilty protocols. Include the following intent filter within your launcher activity.

          ...
          <activity
          ...>
              ...
              <intent-filter>
                  <action android:name="com.cquelsoft.sqlitedevstudio.QUERY"/>
                  <category android:name="android.intent.category.DEFAULT"/>
              </intent-filter>
          </activity>
          ...
          
4. To operate provider has it's 'exported' attribute set to 'true' so we need to incorporate a level of security to prevent other/malicious apps from accessing your own app's databases via the new content provider. Create a 'secrets.xml' file in the project's '/res/values' folder. The resources shown below should be incorporated into the xml file. To access your app's database(s) from SQLiteDevStudio create an encrypted provider access code in SQLiteDevStudio (menu-->Administration-->Provider Access Code). For the authority string that you used in step 1 enter an encryption key and provider access code. These should match the resource entries in the 'secrets.xml' file. The encryption key will be used to encrypt the provider access code. Only the encrypted access code will be stored in SQLiteDevStudio. Each time a request is made to the content provider the access code will be passed. Only a successfully decrypted access code matched against the corresponding resource will allow access to the content provider and your app's database(s).

            <resources>
                  <string name="provider_encryption_key">your_encryption_key</string> <!--Needs to be 16 characters-->
                  <string name="provider_access_code">your_provider_access_code</string>
            </resources>

## Contributing

Pull Requests are welcome. For major changes, please open an issue first to discuss what you would like to change.
