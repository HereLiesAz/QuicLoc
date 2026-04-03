with open('.github/workflows/backup-repo.yml', 'r') as f:
    content = f.read()

# Replace:
# name: repo-backup-${{ github.ref_name }}-${{ github.sha }}
# With:
# name: repo-backup-${{ github.ref_name }}-${{ github.sha }} (But wait, the artifact name cannot contain forward slash!)
# We need to sanitize the branch name. Or we can just use an environment variable.

new_step = """
      - name: Sanitize branch name
        run: |
          SAFE_BRANCH=$(echo "${{ github.ref_name }}" | tr '/' '-')
          echo "SAFE_BRANCH=$SAFE_BRANCH" >> $GITHUB_ENV

      - name: Upload backup as artifact
        uses: actions/upload-artifact@v4
        with:
          name: repo-backup-${{ env.SAFE_BRANCH }}-${{ github.sha }}
          path: repo_backup.txt
          retention-days: 90
"""

import re
content = re.sub(
    r'\s*- name: Upload backup as artifact\n\s*uses: actions/upload-artifact@v4\n\s*with:\n\s*name: repo-backup-\$\{\{ github\.ref_name \}\}-\$\{\{ github\.sha \}\}\n\s*path: repo_backup\.txt\n\s*retention-days: 90',
    new_step,
    content
)

with open('.github/workflows/backup-repo.yml', 'w') as f:
    f.write(content)
