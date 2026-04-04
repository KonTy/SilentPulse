#!/usr/bin/env bash
# Add comprehensive unit tests for all critical paths
# =============================================================================
# set -e removed: aider exits non-zero on "no changes" which would abort the phase
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
SCRIPTS="$REPO/scripts/aider"

echo "→ Phase 2: Generating test suite..."

# ── Test: Domain layer ────────────────────────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  domain/src/main/java/com/moez/QKSMS/interactor/SendMessage.kt \
  domain/src/main/java/com/moez/QKSMS/interactor/ReceiveSimChanged.kt \
  domain/src/main/java/com/moez/QKSMS/interactor/DeleteMessages.kt \
  domain/src/main/java/com/moez/QKSMS/interactor/MarkRead.kt \
  domain/src/main/java/com/moez/QKSMS/model/Conversation.kt \
  domain/src/main/java/com/moez/QKSMS/model/Message.kt \
  --message "$(cat << 'MSG'
Create comprehensive JUnit 4 unit tests for these domain interactors and models.
Place tests in domain/src/test/java/com/moez/QKSMS/interactor/ and domain/src/test/java/com/moez/QKSMS/model/

For each interactor:
- Test happy path with mocked repositories
- Test error cases (null inputs, empty results)
- Test RxJava2 observable contracts (use TestObserver/TestSubscriber)
- Use Mockito for mocking

For models:
- Test data class equality/copy
- Test any computed properties

Use @RunWith(MockitoJUnitRunner::class) and Mockito.mock().
MSG
)"

# ── Test: Data layer ──────────────────────────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  data/src/main/java/com/moez/QKSMS/repository/MessageRepositoryImpl.kt \
  data/src/main/java/com/moez/QKSMS/repository/ConversationRepositoryImpl.kt \
  data/src/main/java/com/moez/QKSMS/util/Preferences.kt \
  --message "$(cat << 'MSG'
Create unit tests for MessageRepositoryImpl and ConversationRepositoryImpl.
Place in data/src/test/java/com/moez/QKSMS/repository/

Key things to test:
- Message sending flow
- Conversation creation/lookup  
- Message deletion
- Preference reading/writing

Mock: ContentResolver, Realm, SmsManager.
Use Robolectric if Android context needed (@RunWith(RobolectricTestRunner::class)).
MSG
)"

# ── Test: Presentation ViewModels ────────────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/compose/ComposeViewModel.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/compose/ComposeState.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/main/MainViewModel.kt \
  presentation/src/main/java/com/moez/QKSMS/feature/main/MainState.kt \
  --message "$(cat << 'MSG'
Create unit tests for ComposeViewModel and MainViewModel.
Place in presentation/src/test/java/com/moez/QKSMS/feature/compose/ and .../feature/main/

Test:
- State transitions via newState{}
- Correct state emitted on action
- Disposal of subscriptions in onCleared()

Use TestScheduler for RxJava timing control.
Add @Before setup with mock dependencies via Dagger or manual construction.
MSG
)"

# ── Test: Build config validation (non-regression) ───────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/build.gradle \
  data/build.gradle \
  --message "$(cat << 'MSG'
Create a Kotlin test file at data/src/test/java/com/moez/QKSMS/BuildConfigTest.kt that:
1. Verifies minSdkVersion >= 21
2. Verifies targetSdkVersion >= 35  
3. Verifies compileSdkVersion >= 35
4. Documents the current dependency versions as constants so we know if they change (read them from BuildConfig or hardcode them as regression tests)

This is a simple documentation-as-test pattern to prevent accidental downgrades.
MSG
)"

# ── Test: SMS/MMS receive regression (critical path) ─────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  domain/src/main/java/com/moez/QKSMS/interactor/ReceiveSms.kt \
  domain/src/main/java/com/moez/QKSMS/interactor/ReceiveMms.kt \
  domain/src/main/java/com/moez/QKSMS/interactor/MarkDelivered.kt \
  domain/src/main/java/com/moez/QKSMS/interactor/MarkSent.kt \
  domain/src/main/java/com/moez/QKSMS/interactor/RetrySending.kt \
  data/src/main/java/com/moez/QKSMS/receiver/SmsReceiver.kt \
  --message "Create regression tests for the core SMS/MMS receive path.
Place in domain/src/test/java/com/moez/QKSMS/interactor/

ReceiveSmsTest.kt - test:
- Happy path: valid SMS address + body -> message stored in repo
- Empty body: should still store
- Blocked sender: message should be marked blocked
- ReceiveSms.Params data class equality

ReceiveMmsTest.kt - test:
- Happy path: valid MMS URI -> message retrieved and stored
- Invalid URI: error handled gracefully
- Multi-part MMS (multiple parts in message)

MarkDeliveredTest.kt, MarkSentTest.kt - test:
- Status transitions work with mocked MessageRepository
- Correct message ID is passed to repo

RetryS endingTest.kt - test:
- Valid message ID triggers resend
- Wrong/missing ID handled

SmsReceiverTest.kt (if testable without full Android context):
- Test the broadcast receiver extracts correct SMS data from Intent extras
- Use Robolectric (@RunWith(RobolectricTestRunner::class)) for Android context
- Mock the injected interactors

All tests use Mockito and TestObserver for RxJava2 assertions."

echo "✓ Phase 2 COMPLETE - test suite generated"
