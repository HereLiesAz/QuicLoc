import urllib.request
req = urllib.request.Request(
    'https://developer.android.com/reference/android/app/admin/DevicePolicyManager',
    headers={'User-Agent': 'Mozilla/5.0'}
)
try:
    with urllib.request.urlopen(req) as response:
        print("Success")
except Exception as e:
    print(e)
