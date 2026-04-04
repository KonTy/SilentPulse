#!/usr/bin/env bash
# Fix all remaining 424 Kotlin compilation errors in presentation module
# =============================================================================
# Uses: github_copilot/claude-sonnet-4.5 (architect) + gpt-4.1 (editor)
# =============================================================================

# set -e removed: aider exits non-zero on "no changes" which would abort the phase
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
SCRIPTS="$REPO/scripts/aider"

echo "→ Collecting current compile errors..."
./gradlew :presentation:compileNoAnalyticsDebugKotlin 2>&1 | grep "^e:" \
  | sed 's|e: file:///home/blin/Documents/source/qksms/||' \
  > /tmp/current_errors.txt

ERROR_COUNT=$(wc -l < /tmp/current_errors.txt)
echo "→ Found $ERROR_COUNT errors. Starting aider fix..."

# ── Batch 1: Adapters with unresolved view references ─────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/conversations/ConversationsAdapter.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/blocking/messages/BlockedMessagesAdapter.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/main/SearchAdapter.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/scheduled/ScheduledMessageAdapter.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/scheduled/ScheduledMessageAttachmentAdapter.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/compose/AttachmentAdapter.kt \
  presentation/src/main/java/com/moez/QKSMS/common/MenuItemAdapter.kt \
  --message "$(cat << 'MSG'
Fix all Kotlin compilation errors in these adapter files. These files previously used Kotlin synthetic view binding (removed in Kotlin 1.8+). They reference view IDs directly like `title.text = "..."` which no longer works.

Fix pattern: In each RecyclerView.ViewHolder class, add explicit `val viewName: ViewType = itemView.findViewById(R.id.viewName)` properties for every view that is accessed.

Current errors from compiler:
$(grep "ConversationsAdapter\|BlockedMessagesAdapter\|SearchAdapter\|ScheduledMessage\|AttachmentAdapter\|MenuItemAdapter" /tmp/current_errors.txt)

Rules:
1. DO NOT change any business logic
2. Add view properties to the ViewHolder inner class using itemView.findViewById
3. Import android.widget.ImageView, android.view.View etc as needed
4. The view ID names (e.g. R.id.title, R.id.date) match exactly the property names being accessed
MSG
)"

# ── Batch 2: Compose adapters ─────────────────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/compose/editing/ComposeItemAdapter.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/compose/MessagesAdapter.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/compose/editing/PhoneNumberPickerAdapter.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/compose/editing/PhoneNumberAdapter.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/compose/editing/ChipsAdapter.kt \
  --message "$(cat << 'MSG'
Fix Kotlin synthetic view binding errors in these compose editing adapters.
Add itemView.findViewById() calls in ViewHolder classes for all accessed views.
Current errors:
$(grep "ComposeItemAdapter\|MessagesAdapter\|PhoneNumber\|ChipsAdapter" /tmp/current_errors.txt)
Rules: Same as before - only fix syntax errors, preserve all logic.
MSG
)"

# ── Batch 3: Part binders ─────────────────────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/compose/part/FileBinder.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/compose/part/VCardBinder.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/compose/part/MediaBinder.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/compose/part/PartsAdapter.kt \
  --message "$(cat << 'MSG'
Fix Kotlin synthetic view binding errors in these part binder files.
These binders have a 'view' property or 'container' - use view.findViewById<Type>(R.id.xxx) for each accessed view ID.
Also fix: 'Unresolved reference R' in PartsAdapter - add import for R class.
Current errors:
$(grep "FileBinder\|VCardBinder\|MediaBinder\|PartsAdapter" /tmp/current_errors.txt)
MSG
)"

# ── Batch 4: ConversationInfo + backup adapters ───────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/conversationinfo/ConversationInfoAdapter.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/backup/BackupAdapter.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/changelog/ChangelogAdapter.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/blocking/numbers/BlockedNumbersAdapter.kt \
  --message "Fix synthetic view binding errors. Add itemView.findViewById() for all accessed views in ViewHolder classes. Current errors: $(grep "ConversationInfoAdapter\|BackupAdapter\|ChangelogAdapter\|BlockedNumbersAdapter" /tmp/current_errors.txt)"

# ── Batch 5: Theme adapter + picker ───────────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/themepicker/ThemeAdapter.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/themepicker/ThemePickerController.kt \
  --message "$(cat << 'MSG'
Fix errors in ThemeAdapter.kt:
1. Add itemView.findViewById for 'palette' (FlexboxLayout) and 'theme' (PreferenceView) in ViewHolder
2. 'check' reference - the R.id.check view is an ImageView, use itemView.findViewById
3. 'Function invocation apply expected' - this is a Kotlin DSL issue, check if view.apply{} is being used on a non-view type

Fix errors in ThemePickerController.kt:
1. 'Function invocation apply expected' - likely a view property the controller can't find after synthetics removal
2. Add proper view lookups using requireView().findViewById()

Current errors:
$(grep "ThemeAdapter\|ThemePickerController" /tmp/current_errors.txt)
MSG
)"

# ── Batch 6: Dialogs and widgets ──────────────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/common/widget/TextInputDialog.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/settings/autodelete/AutoDeleteDialog.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/changelog/ChangelogDialog.kt \
  --message "$(cat << 'MSG'
Fix synthetic view binding errors in dialogs. These dialogs inflate a layout view and then access children by their ID.
Pattern: val field: QkEditText = view.findViewById(R.id.field)
For ChangelogDialog: add properties for 'more', 'dismiss', 'changelog', 'version', 'recycler' etc.
Current errors:
$(grep "TextInputDialog\|AutoDeleteDialog\|ChangelogDialog" /tmp/current_errors.txt)
MSG
)"

# ── Batch 7: Controllers ──────────────────────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/blocking/manager/BlockingManagerController.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/settings/SettingsController.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/blocking/messages/BlockedMessagesController.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/blocking/numbers/BlockedNumbersController.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/settings/swipe/SwipeActionsController.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/backup/BackupController.kt \
  presentation/src/main/java/com/moez/QKSMS/common/base/QkActivity.kt \
  --message "$(cat << 'MSG'
Fix view reference errors in Conductor Controllers and QkActivity:
1. For Controllers: use requireView().findViewById<Type>(R.id.xxx) or view?.findViewById in lifecycle methods. Add a lazy helper 'private fun <T: View> view(id: Int) = requireView().findViewById<T>(id)' if needed.
2. 'Cannot access protected toolbar in QkActivity' - change toolbar from protected to internal in QkActivity.kt
3. 'QkSwitch checkbox' - add requireView().findViewById<QkSwitch>(R.id.checkbox) 
4. PreferenceView.titleView is private - use the public 'title' property setter instead.
Current errors:
$(grep "BlockingManagerController\|SettingsController\|BlockedMessages\|BlockedNumbers\|SwipeActions\|BackupController\|QkActivity" /tmp/current_errors.txt)
MSG
)"

# ── Batch 8: Activities ───────────────────────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/compose/ComposeActivity.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/main/MainActivity.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/qkreply/QkReplyActivity.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/gallery/GalleryActivity.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/notificationprefs/NotificationPrefsActivity.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/plus/PlusActivity.kt \
  --message "$(cat << 'MSG'
Fix errors in Activity files:
1. 'Conflicting import ViewModelProvider' - remove DUPLICATE import lines (both lines import the same class)
2. Toolbar nullable: change 'toolbar.xxx' to 'toolbar?.xxx' throughout
3. 'Unresolved reference View' - add 'import android.view.View' if missing (for View.GONE, View.VISIBLE)
4. 'colorPrimary' unresolved - use resolveThemeColor(com.google.android.material.R.attr.colorPrimary) 
5. 'onRestoreInstanceState' overrides nothing - remove the override or add correct param (Bundle?)
6. 'include' unresolved in MainActivity - this is a layout include tag; use findViewById() directly
7. GalleryActivity registerOnPageChangeCallback: ViewPager2 API - use binding.viewPager.registerOnPageChangeCallback
8. toolbarTitle nullable: change 'toolbarTitle.text' to 'toolbarTitle?.text'
Current errors:
$(grep "ComposeActivity\|MainActivity\|QkReply\|GalleryActivity\|NotificationPrefs\|PlusActivity" /tmp/current_errors.txt)
MSG
)"

# ── Batch 9: GalleryPagerAdapter (ExoPlayer API update) ──────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/gallery/GalleryPagerAdapter.kt \
  --message "$(cat << 'MSG'
Fix GalleryPagerAdapter.kt - ExoPlayer 2.19.x API migration:
1. ExoPlayerFactory -> ExoPlayer.Builder(context).build()
2. ExtractorMediaSource -> ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context)).createMediaSource(MediaItem.fromUri(uri))
3. 'image' unresolved view ref: add itemView.findViewById<PhotoView>(R.id.image) or view.findViewById in the appropriate inflate method
4. KClass.javaClass deprecated -> use KClass.java instead
Current errors:
$(grep "GalleryPagerAdapter" /tmp/current_errors.txt)
MSG
)"

# ── Batch 10: ComposeViewModel + misc ────────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/compose/ComposeViewModel.kt \
  presentation/src/main/java/com/moez/QKSMS/common/util/NotificationManagerImpl.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/compose/editing/DetailedChipView.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/blocking/manager/BlockingManagerPreferenceView.kt \
  --message "$(cat << 'MSG'
Fix remaining misc errors:
1. ComposeViewModel.kt line ~210: type inference issue with switchMap - add explicit type parameters: .switchMap<X, Y> { ... }
2. NotificationManagerImpl.kt: .filterNotNull() before .forEach { notification.addAction(it) }  
3. DetailedChipView.kt: type inference issue - add explicit types to lambda params
4. BlockingManagerPreferenceView.kt: R.attr.selectableItemBackground -> use androidx.appcompat.R.attr.selectableItemBackground
Current errors:
$(grep "ComposeViewModel\|NotificationManagerImpl\|DetailedChipView\|BlockingManagerPreference" /tmp/current_errors.txt)
MSG
)"

# ── Verify ────────────────────────────────────────────────────────────────────
echo ""
echo "→ Running final compile check..."
REMAINING=$(./gradlew :presentation:compileNoAnalyticsDebugKotlin 2>&1 | grep "^e:" | wc -l)
echo "→ Remaining errors: $REMAINING"
if [ "$REMAINING" -eq 0 ]; then
    echo "✓ Phase 1 COMPLETE - presentation module compiles!"
else
    echo "✗ Still $REMAINING errors. Check logs."
    ./gradlew :presentation:compileNoAnalyticsDebugKotlin 2>&1 | grep "^e:" | head -30
fi
