import re

with open('app/src/main/AndroidManifest.xml', 'r') as f:
    content = f.read()

# Add permissions
if 'android.permission.CAMERA' not in content:
    content = content.replace(
        '<uses-permission android:name="android.permission.READ_CONTACTS" />',
        '<uses-permission android:name="android.permission.READ_CONTACTS" />\n    <uses-permission android:name="android.permission.CAMERA" />\n    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />'
    )

# Add Activity and Service
if 'TrackingLockActivity' not in content:
    content = content.replace(
        '</application>',
        '''    <activity
            android:name=".TrackingLockActivity"
            android:theme="@style/Theme.QuicLoc"
            android:showOnLockScreen="true"
            android:showForAllUsers="true"
            android:excludeFromRecents="true"
            android:launchMode="singleInstance"
            android:exported="false" />

        <service
            android:name=".TrackingService"
            android:exported="false"
            android:foregroundServiceType="location|camera" />

    </application>'''
    )

with open('app/src/main/AndroidManifest.xml', 'w') as f:
    f.write(content)
