#!/usr/bin/env python3
"""Fix nullable types in migrated view properties."""
import re, os

src_dir = "presentation/src"
count = 0

for root, dirs, files in os.walk(src_dir):
    for f in files:
        if not f.endswith('.kt'):
            continue
        filepath = os.path.join(root, f)
        with open(filepath) as fh:
            content = fh.read()
        
        if '// View references (migrated from synthetics)' not in content:
            continue
        
        original = content
        
        # Fix Activity/Widget/Dialog pattern: Type? get() = findViewById(R.id.xxx)
        content = re.sub(
            r'(private val \w+): (\w+)\? get\(\) = findViewById\(R\.id\.(\w+)\)',
            r'\1: \2 get() = findViewById(R.id.\3)',
            content
        )
        
        # Fix Controller pattern: Type? get() = view?.findViewById(R.id.xxx)
        content = re.sub(
            r'(private val \w+): (\w+)\? get\(\) = view\?\.findViewById\(R\.id\.(\w+)\)',
            r'\1: \2 get() = view!!.findViewById(R.id.\3)',
            content
        )
        
        if content != original:
            with open(filepath, 'w') as fh:
                fh.write(content)
            count += 1

print(f"Fixed nullable types in {count} files")
