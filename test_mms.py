import urllib.request
import urllib.parse
import json
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

url = 'https://api.duckduckgo.com/?q=android+send+mms+programmatically+pdu&format=json'
req = urllib.request.Request(
    'https://html.duckduckgo.com/html/?q=android+send+mms+programmatically+SmsManager',
    headers={'User-Agent': 'Mozilla/5.0'}
)
try:
    with urllib.request.urlopen(req, context=ctx) as response:
        html = response.read().decode('utf-8')
        for line in html.split('\n'):
            if 'class="result__url"' in line:
                print(line.strip())
except Exception as e:
    print(e)
