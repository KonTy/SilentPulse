#!/usr/bin/env python3
"""
Fix view property issues:
1. Remove toolbar/toolbarTitle from subclasses that inherit from QkActivity
2. Fix adapter ViewHolders to use itemView.findViewById
"""
import re, os

PROJECT = "/home/blin/Documents/source/qksms"
SRC = os.path.join(PROJECT, "presentation/src")

# Properties that exist in QkActivity base class - must not be redefined
BASE_PROPS = {'toolbar', 'toolbarTitle'}

count = 0
for root, dirs, files in os.walk(SRC):
    for f in files:
        if not f.endswith('.kt'):
            continue
        filepath = os.path.join(root, f)
        with open(filepath) as fh:
            content = fh.read()
        
        # Skip base class itself
        if 'abstract class QkActivity' in content:
            continue
        
        # Only fix files that extend QkActivity/QkThemedActivity
        if ': QkThemedActivity()' not in content and ': QkActivity()' not in content:
            continue
        
        original = content
        
        # Remove shadowing properties for toolbar and toolbarTitle
        for prop in BASE_PROPS:
            # Pattern: private val toolbar: Toolbar get() = findViewById(R.id.toolbar)
            content = re.sub(
                rf'    private val {prop}: \w+ get\(\) = findViewById\(R\.id\.{prop}\)\n',
                '',
                content
            )
        
        if content != original:
            with open(filepath, 'w') as fh:
                fh.write(content)
            count += 1
            print(f"Fixed: {os.path.basename(filepath)}")

print(f"\nRemoved shadowing properties in {count} files")
