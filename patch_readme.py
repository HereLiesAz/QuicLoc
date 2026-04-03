with open('README.md', 'r') as f:
    content = f.read()

import re

new_features = """
- **Fully background** — operates silently when the screen is off. Auto-replies work without the app being open or unlocked.
- **Passphrase & Device Lock** — Set a 10-150 character single-use passphrase and a 6-digit PIN. Sending the passphrase starts a 5-minute location tracking interval and forces a lock screen on the device. Failing the PIN 3 times captures a photo of the intruder and escalates tracking to 1-minute intervals with MMS image updates.
- **No data collection** — no analytics, no crash reporters, no servers. Nothing leaves your device except the location reply sent directly to the requesting contact.
"""

content = re.sub(
    r'- \*\*Fully background\*\* — operates silently when the screen is off\. Auto-replies work without the app being open or unlocked\.\n- \*\*No data collection\*\* — no analytics, no crash reporters, no servers\. Nothing leaves your device except the location reply sent directly to the requesting contact\.',
    new_features.strip(),
    content
)

with open('README.md', 'w') as f:
    f.write(content)
