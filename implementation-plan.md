# Implementation Plan

## Overview

Build an Android app with Kotlin and Jetpack Compose for sellers who sell clothing during live streams. The app should make it easy to add garments, record sales, and review results.

This document is a plan, not an authorization to implement the app. Publishing to an app store requires a separate decision.

## 1. Functional Scope

### Seller Account

Collect the following information during account creation, without a separate profile-completion screen:

| Field | Requirement |
| --- | --- |
| Authentication details | Managed by Clerk; sign-in method to be decided |
| WhatsApp number with country code | Required |
| Name | Optional |
| Username | Optional |
| Social media profile links | Optional, for reference |

After registration and any required authentication verification, open Home. Sellers can edit these details in Profile; WhatsApp remains required. Format validation does not verify ownership of the WhatsApp number.

### Garments

| Field | Requirement |
| --- | --- |
| Code | Required |
| Selling price | Required |
| Stock | Defaults to 1 |
| Name | Optional |
| Photo | Optional |
| Size | Optional |
| Color | Optional |
| Description | Optional |
| Initial status | In catalog |

Supported statuses:

- **In catalog:** available for sale.
- **Sold:** no units remain available.
- **Archived:** hidden from the catalog while preserving history.

Selling a garment deducts the quantity sold. If units remain, it stays in the catalog. When stock reaches zero, it becomes Sold.

## 2. Screens and Navigation

### Authentication Screens

- Welcome.
- Sign in.
- Create account, including the required WhatsApp number and optional reference details.
- Account recovery and verification appropriate to the selected authentication method.

Authentication screens do not display the bottom navigation or floating add button.

### Persistent Bottom Navigation

Use exactly three destinations, each with an icon and label:

| Destination | Contents |
| --- | --- |
| Home | Statistics and sales history |
| Garments | Inventory, search, filters, editing, and sales actions |
| Profile | Seller details, theme, notifications, and sign out |

Highlight the active destination in pink.

### Global Floating Add Button

Display a floating **+** button on all three main screens, above the bottom navigation at the bottom right.

- Always opens Create garment.
- Uses the same reusable creation form regardless of the originating screen.
- Returns to the originating destination after saving or canceling.
- Does not appear inside the garment form.
- Is the only garment-creation entry point; the Garments list has no additional create button.
- Must not obscure list content or navigation.

### Home

- Period selector: Today, This week, This month.
- Summary cards for sales revenue and units sold.
- Profit only if cost tracking is agreed upon.
- Sales history showing garment code or name, quantity, amount, and date.
- Simple presentation without unnecessary charts.

### Garments

- Search by code and optional name.
- Filters: In catalog, Sold, Archived.
- Reusable cards showing an optional photo, code, optional name, price, stock, and status.
- Tapping a card opens editing.
- Actions to record a sale and archive a garment.
- Creation is handled exclusively by the global floating add button.

### Profile

- Edit WhatsApp and optional seller details.
- Select Light or Dark mode.
- View notifications and configure notification permissions.
- Sign out.

## 3. Visual System and Reusable Components

Use a pink visual direction inspired by Gentle Shell. Exact palette values must be agreed upon during design; do not treat proposed colors as official Gentle Shell tokens.

- **Light mode:** light surfaces with pink accents.
- **Dark mode:** dark surfaces with pink accents adjusted for contrast.
- Communicate status through text as well as color.
- Use touch-friendly controls suitable for operating during a live stream.
- Keep garment cards consistent, including when no photo is provided.

Define colors centrally. Give each reusable component its own file rather than placing all components in one large file. Screens consume shared components and theme values instead of duplicating styles or controls.

### Proposed Structure

```text
ui/
  theme/
    Color.kt
    Theme.kt
    Type.kt
    Dimensions.kt
    Shape.kt

  components/
    AppButton.kt
    AppTextField.kt
    AppCard.kt
    GarmentCard.kt
    StatCard.kt
    StatusBadge.kt
    PhotoPicker.kt
    AddGarmentFab.kt
    AppBottomBar.kt
    ConfirmationDialog.kt

  navigation/
    AppNavigation.kt
    AppScaffold.kt

  screens/
    auth/
    home/
    garments/
    profile/

data/
  auth/
  garments/
  sales/
  profile/
  notifications/
```

The shared app scaffold owns the bottom navigation and global floating add button.

## 4. Implementation Phases

### Phase 1 — Design and Business Rules

- Design authentication, registration, and the three main destinations.
- Design garment creation and editing.
- Show the screens in light and dark modes.
- Agree on the palette, typography, and shared components.
- Define validation and sales behavior.
- Resolve open decisions before implementing the affected functionality.

**Deliverable:** approved screen designs and behavior before production UI implementation.

### Phase 2 — Android Foundation and Shared Components

- Create the Kotlin project with Jetpack Compose and Material 3.
- Implement shared theme values and components.
- Build navigation, bottom navigation, and the global add button.
- Prepare screens with sample data.
- Include empty, loading, and error states.

**Deliverable:** a consistent, navigable app shell in both themes.

### Phase 3 — Registration, Sign-In, and Seller Account

- Integrate Clerk.
- Implement registration with required WhatsApp and optional reference details.
- Store seller profiles in Supabase, linked to the Clerk identity.
- Persist authentication sessions across app launches.
- Support profile editing.
- Implement sign out, clear account-specific local state, and prevent back navigation into authenticated screens after sign out.
- Restrict each seller's access to their own data and images.

**Deliverable:** working registration, sign-in, profile editing, session persistence, and sign out.

### Phase 4 — Inventory and Photos

- Create garments from any main destination through the global add button.
- Reuse the garment form for creation and editing.
- Upload, replace, and remove optional photos using Supabase Storage.
- Implement search and status filters.
- Archive garments without deleting sales history.
- Validate inputs and present clear errors.

**Deliverable:** persistent and usable inventory management.

### Phase 5 — Sales and Statistics

- Record the quantity and amount of each sale.
- Update sale records and stock together to avoid inconsistent results.
- Update garment status when stock reaches zero.
- Preserve the price at the time of each sale so later price edits do not alter historical statistics.
- Support correcting an accidental sale, including the corresponding stock and statistics changes.
- Show revenue, units sold, and sales history by period.
- Calculate profit only if cost tracking is agreed upon.

**Deliverable:** sales and statistics that remain consistent with inventory and historical prices.

### Phase 6 — Theme Preferences and Notifications

- Add a Light/Dark selector in Profile.
- Persist the selection and restore it on app launch.
- Apply theme changes throughout the interface.
- Agree on which events generate notifications.
- Integrate notification permissions and push delivery through Firebase Cloud Messaging.
- Display notifications within Profile.

**Deliverable:** persistent appearance preferences and notifications for the agreed events.

### Phase 7 — Android Validation

Test functionality during each phase, then validate the complete flow:

- Registration requires WhatsApp and accepts empty optional fields.
- Sign-in, session persistence, and sign out work correctly.
- Garments can be created with only code and price; stock defaults to 1 and status defaults to In catalog.
- Missing name or photo does not break garment forms or cards.
- Editing, archiving, and selling work correctly with stock of 1 and greater quantities.
- Sale corrections keep inventory and statistics consistent.
- Historical sale amounts remain unchanged after garment price edits.
- Seller data and images remain isolated between accounts.
- Theme selection survives an app restart.
- Bottom navigation and the floating add button remain usable on small screens and do not obscure content or interfere with the keyboard.
- Empty, loading, error, and notification-permission states are usable.

**Deliverable:** a validated, installable Android build. App store publication is excluded from this phase.

## 5. Technology

| Area | Technology |
| --- | --- |
| Android development | Kotlin |
| UI | Jetpack Compose and Material 3 |
| Authentication | Clerk |
| Profiles, inventory, and sales | Supabase |
| Garment photos | Supabase Storage |
| Android push notifications | Firebase Cloud Messaging |

n8n is deferred and is not required for the initial inventory and manual sales workflows.

## 6. Open Decisions

1. **Profit definition:** show revenue only, or subtract garment cost? Actual profit requires cost information; an optional cost field is proposed but not yet approved. Missing cost must not be presented as known profit.
2. **Authentication method:** choose the Clerk sign-in and registration method.
3. **Notification events:** decide which events should trigger notifications.
4. **Code uniqueness:** confirm the proposal that garment codes are unique per seller.

## 7. Out of Scope for the Initial Version

- Public storefront or public seller profiles.
- Payment processing.
- n8n automations.
- App store publication without separate authorization.
