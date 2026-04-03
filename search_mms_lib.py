import urllib.request
req = urllib.request.Request(
    'https://search.maven.org/solrsearch/select?q=android-smsmms&rows=5&wt=json',
    headers={'User-Agent': 'Mozilla/5.0'}
)
try:
    with urllib.request.urlopen(req) as response:
        print(response.read().decode('utf-8'))
except Exception as e:
    print(e)
