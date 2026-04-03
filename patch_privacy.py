with open('PRIVACY_POLICY.md', 'r') as f:
    content = f.read()

# Add camera, passphrase, and photo data
import re

content = re.sub(
    r'- Your camera or microphone',
    '- Your microphone',
    content
)

camera_data = """### 5. Camera (Photos)
When QuicLoc enters Panic Mode (triggered by entering an incorrect PIN 3 times on the lock screen), the app uses the front-facing camera to capture a photo of the user. This photo is attached to an MMS message along with the location link and sent exclusively to the trusted contact who requested the location. The photo is never uploaded to external servers, processed by third-party APIs, or shared with the developer.

### 6. Passphrase and PIN
You can optionally configure a single-use passphrase and a 6-digit PIN. These are securely encrypted on your device alongside your whitelist. They are never transmitted off your device.
"""

content = content.replace(
    '---',
    '---\n\n' + camera_data,
    1 # Only the first occurrence? No we need to place it in Data the App Accesses
)

# A better way is to replace the start of Data the App Does NOT Access
content = content.replace(
    '## Data the App Does NOT Access',
    camera_data + '\n## Data the App Does NOT Access'
)

# And add CAMERA permission
perm_addition = "| `CAMERA` | To capture a photo of the intruder if Panic Mode is triggered |"
content = content.replace(
    '| `BIND_NOTIFICATION_LISTENER_SERVICE` | To detect the trigger word in notifications from non-SMS messaging apps |',
    '| `BIND_NOTIFICATION_LISTENER_SERVICE` | To detect the trigger word in notifications from non-SMS messaging apps |\n' + perm_addition
)

with open('PRIVACY_POLICY.md', 'w') as f:
    f.write(content)
