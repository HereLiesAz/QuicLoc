with open('.github/workflows/build-apk.yml', 'r') as f:
    content = f.read()

new_step = """
      - name: Sanitize branch name
        run: |
          SAFE_BRANCH=$(echo "${{ github.ref_name }}" | tr '/' '-')
          echo "SAFE_BRANCH=$SAFE_BRANCH" >> $GITHUB_ENV

      - name: Upload APK as Artifact
        uses: actions/upload-artifact@v4
        with:
          name: quicloc-apk-${{ env.SAFE_BRANCH }}-${{ github.sha }}
"""

import re
content = re.sub(
    r'\s*- name: Upload APK as Artifact\n\s*uses: actions/upload-artifact@v4\n\s*with:\n\s*name: quicloc-apk-\$\{\{ github\.ref_name \}\}-\$\{\{ github\.sha \}\}',
    new_step,
    content
)

with open('.github/workflows/build-apk.yml', 'w') as f:
    f.write(content)
