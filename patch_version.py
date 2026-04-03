with open('app/version.properties', 'r') as f:
    lines = f.readlines()

new_lines = []
for line in lines:
    if line.startswith('VERSION_B='):
        parts = line.split('=')
        val = int(parts[1].strip())
        new_lines.append(f'VERSION_B={val + 1}\n')
    elif line.startswith('VERSION_C='):
        new_lines.append('VERSION_C=0\n')
    else:
        new_lines.append(line)

with open('app/version.properties', 'w') as f:
    f.writelines(new_lines)
