#!/usr/bin/env python3
"""
Migrate Kotlin Android Synthetic view references to findViewById.
Reads a build error log, identifies unresolved view IDs, looks up their types
from layout XMLs, and adds lazy findViewById properties to each class.
"""

import xml.etree.ElementTree as ET
import os
import re
import sys
from collections import defaultdict

PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))
LAYOUT_DIR = os.path.join(PROJECT_ROOT, 'presentation/src/main/res/layout')
SRC_ROOT = os.path.join(PROJECT_ROOT, 'presentation/src')

ANDROID_NS = '{http://schemas.android.com/apk/res/android}'

# FQCN mapping for short class names used in XML
WIDGET_IMPORTS = {
    'Toolbar': 'androidx.appcompat.widget.Toolbar',
    'RecyclerView': 'androidx.recyclerview.widget.RecyclerView',
    'AppBarLayout': 'com.google.android.material.appbar.AppBarLayout',
    'CollapsingToolbarLayout': 'com.google.android.material.appbar.CollapsingToolbarLayout',
    'TabLayout': 'com.google.android.material.tabs.TabLayout',
    'ViewPager': 'androidx.viewpager.widget.ViewPager',
    'ViewPager2': 'androidx.viewpager2.widget.ViewPager2',
    'FloatingActionButton': 'com.google.android.material.floatingactionbutton.FloatingActionButton',
    'SwipeRefreshLayout': 'androidx.swiperefreshlayout.widget.SwipeRefreshLayout',
    'CoordinatorLayout': 'androidx.coordinatorlayout.widget.CoordinatorLayout',
    'NestedScrollView': 'androidx.core.widget.NestedScrollView',
    'CardView': 'androidx.cardview.widget.CardView',
    'ConstraintLayout': 'androidx.constraintlayout.widget.ConstraintLayout',
    'Group': 'androidx.constraintlayout.widget.Group',
    'Guideline': 'androidx.constraintlayout.widget.Guideline',
    'ChangeHandlerFrameLayout': 'com.bluelinelabs.conductor.ChangeHandlerFrameLayout',
    # Default android.widget.* and android.view.*
    'View': 'android.view.View',
    'ViewGroup': 'android.view.ViewGroup',
    'Button': 'android.widget.Button',
    'TextView': 'android.widget.TextView',
    'EditText': 'android.widget.EditText',
    'ImageView': 'android.widget.ImageView',
    'ImageButton': 'android.widget.ImageButton',
    'LinearLayout': 'android.widget.LinearLayout',
    'RelativeLayout': 'android.widget.RelativeLayout',
    'FrameLayout': 'android.widget.FrameLayout',
    'ProgressBar': 'android.widget.ProgressBar',
    'SeekBar': 'android.widget.SeekBar',
    'Spinner': 'android.widget.Spinner',
    'Switch': 'android.widget.Switch',
    'CheckBox': 'android.widget.CheckBox',
    'ScrollView': 'android.widget.ScrollView',
    'HorizontalScrollView': 'android.widget.HorizontalScrollView',
    'Space': 'android.widget.Space',
}


def parse_layouts():
    """Parse all layout XML files and return view_id -> view_type mapping."""
    view_map = {}
    for f in os.listdir(LAYOUT_DIR):
        if not f.endswith('.xml'):
            continue
        path = os.path.join(LAYOUT_DIR, f)
        try:
            tree = ET.parse(path)
            for elem in tree.iter():
                aid = elem.get(ANDROID_NS + 'id')
                if aid and aid.startswith('@+id/'):
                    view_id = aid[5:]
                    tag = elem.tag
                    # Remove package prefix for known types
                    if '.' in tag:
                        short_name = tag.rsplit('.', 1)[1]
                    else:
                        short_name = tag
                    view_map[view_id] = (short_name, tag)
        except Exception:
            pass
    return view_map


def parse_errors(error_file):
    """Parse build error log and return dict of file -> set of unresolved view IDs."""
    file_refs = defaultdict(set)
    
    with open(error_file) as f:
        content = f.read()
    
    # Join wrapped lines (terminal wrapping breaks lines)
    lines = content.split('\n')
    joined = []
    for line in lines:
        if line.startswith('e: ') or line.startswith('w: ') or line.startswith('FAILURE') or not joined:
            joined.append(line)
        elif joined and joined[-1].startswith('e: '):
            joined[-1] += line
        else:
            joined.append(line)
    
    pattern = re.compile(r"^e: file://(/.+?\.kt):\d+:\d+ Unresolved reference '(\w+)'")
    for line in joined:
        m = pattern.match(line.strip())
        if m:
            filepath = m.group(1)
            ref = m.group(2)
            file_refs[filepath].add(ref)
    return file_refs


def get_import_for_type(short_name, full_tag):
    """Get the import statement needed for a view type."""
    if short_name in WIDGET_IMPORTS:
        return WIDGET_IMPORTS[short_name]
    # If it's a full qualified name already
    if '.' in full_tag:
        return full_tag
    # Custom project widgets - check if they're in the project
    custom_prefix = 'com.moez.QKSMS.common.widget.'
    return custom_prefix + short_name


def classify_file(filepath, content):
    """Classify a file as Activity, Controller, Adapter, Widget, or Other."""
    if ': QkActivity()' in content or ': QkThemedActivity()' in content:
        return 'activity'
    if 'QkController' in content or 'LifecycleController' in content:
        return 'controller'
    if 'RecyclerView.Adapter' in content or 'QkRealmAdapter' in content or 'QkAdapter' in content:
        return 'adapter'
    if ': LinearLayout' in content or ': FrameLayout' in content or ': ConstraintLayout' in content or ': RelativeLayout' in content or ': View(' in content:
        return 'widget'
    if ': Dialog' in content or 'AlertDialog' in content or 'DialogFragment' in content:
        return 'dialog'
    return 'other'


def add_view_properties(filepath, content, view_ids, view_map, file_type):
    """Add lazy findViewById properties to a class for the given view IDs."""
    
    # Filter to only IDs that exist in the view_map
    valid_ids = {vid for vid in view_ids if vid in view_map}
    if not valid_ids:
        return content, False

    # Determine the view lookup expression based on file type
    if file_type == 'activity':
        lookup_prefix = 'findViewById'
    elif file_type == 'controller':
        lookup_prefix = 'view!!.findViewById'
    elif file_type == 'widget':
        lookup_prefix = 'findViewById'
    elif file_type == 'adapter':
        # For adapters, view refs are usually in onBindViewHolder with holder.itemView
        # These are complex - skip automated migration
        return content, False
    else:
        lookup_prefix = 'findViewById'

    # Build property declarations
    properties = []
    imports_needed = set()
    
    for vid in sorted(valid_ids):
        short_name, full_tag = view_map[vid]
        import_path = get_import_for_type(short_name, full_tag)
        
        if file_type in ('controller',):
            prop = f'    private val {vid}: {short_name}? get() = view?.findViewById(R.id.{vid})'
        elif file_type == 'activity':
            prop = f'    private val {vid}: {short_name}? get() = findViewById(R.id.{vid})'
        elif file_type == 'widget':
            prop = f'    private val {vid}: {short_name}? get() = findViewById(R.id.{vid})'
        else:
            prop = f'    private val {vid}: {short_name}? get() = findViewById(R.id.{vid})'
        
        properties.append(prop)
        
        # Only add import if not android.view.View (usually already imported)
        # and the type needs importing
        if import_path and import_path not in ('android.view.View',):
            imports_needed.add(import_path)

    if not properties:
        return content, False
    
    # Add imports
    lines = content.split('\n')
    import_section_end = 0
    for i, line in enumerate(lines):
        if line.startswith('import '):
            import_section_end = i + 1
    
    existing_imports = set(line.strip() for line in lines if line.startswith('import '))
    new_imports = []
    for imp in sorted(imports_needed):
        import_line = f'import {imp}'
        if import_line not in existing_imports:
            new_imports.append(import_line)
    
    if new_imports:
        for imp in reversed(new_imports):
            lines.insert(import_section_end, imp)
    
    # Find the class body start (first { after class declaration)
    class_body_start = None
    brace_count = 0
    in_class_decl = False
    for i, line in enumerate(lines):
        stripped = line.strip()
        if re.match(r'^(abstract\s+)?class\s+\w+', stripped) or \
           re.match(r'^(open\s+)?class\s+\w+', stripped) or \
           re.match(r'^(internal\s+)?class\s+\w+', stripped):
            in_class_decl = True
        if in_class_decl:
            if '{' in line:
                class_body_start = i
                break
    
    if class_body_start is not None:
        # Insert properties after the class opening brace
        insert_at = class_body_start + 1
        properties_block = '\n'.join(properties)
        lines.insert(insert_at, '')
        lines.insert(insert_at + 1, '    // View references (migrated from synthetics)')
        for j, prop in enumerate(properties):
            lines.insert(insert_at + 2 + j, prop)
        lines.insert(insert_at + 2 + len(properties), '')

    return '\n'.join(lines), True


def main():
    error_file = sys.argv[1] if len(sys.argv) > 1 else '/tmp/build_output2.txt'
    
    print("Parsing layout XMLs...")
    view_map = parse_layouts()
    print(f"Found {len(view_map)} view IDs")
    
    print("Parsing build errors...")
    file_refs = parse_errors(error_file)
    print(f"Found unresolved references in {len(file_refs)} files")
    
    modified = 0
    skipped = 0
    
    for filepath, refs in sorted(file_refs.items()):
        if not os.path.exists(filepath):
            continue
            
        with open(filepath) as f:
            content = f.read()
        
        file_type = classify_file(filepath, content)
        
        # Filter refs to only those that are view IDs
        view_refs = {r for r in refs if r in view_map}
        
        if not view_refs:
            continue
        
        new_content, changed = add_view_properties(filepath, content, view_refs, view_map, file_type)
        
        if changed:
            with open(filepath, 'w') as f:
                f.write(new_content)
            modified += 1
            print(f"  Modified: {os.path.basename(filepath)} ({file_type}) - {len(view_refs)} view refs: {', '.join(sorted(view_refs))}")
        else:
            skipped += 1
            print(f"  Skipped:  {os.path.basename(filepath)} ({file_type}) - {len(view_refs)} refs (adapter/other)")
    
    print(f"\nDone! Modified {modified} files, skipped {skipped}")


if __name__ == '__main__':
    main()
