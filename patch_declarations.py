with open('DECLARATIONS.md', 'r') as f:
    content = f.read()

new_section = """
---

## 4. Camera Permission Declaration

**Location in Play Console:**
> App content → App access

**Describe why your app needs camera access:**

> QuicLoc uses the device's front-facing camera solely for its Panic Mode security feature. If an unauthorized user attempts to bypass the device lock screen by entering an incorrect PIN 3 times, QuicLoc silently captures a photo of the intruder and sends it to the trusted contact who initiated the location request via MMS. The photo is captured locally, transmitted directly to the trusted contact's phone number, and is never uploaded, stored on external servers, or shared with the developer or third parties.
"""

# Insert before "4. Data Safety Section"
content = content.replace('## 4. Data Safety Section', new_section.strip() + '\n\n---\n\n## 5. Data Safety Section')
content = content.replace('## 5. App Category and Content Rating', '## 6. App Category and Content Rating')

with open('DECLARATIONS.md', 'w') as f:
    f.write(content)
