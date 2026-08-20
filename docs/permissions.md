# Permissions

Android grants these to the **Termux:API** app, not to Termux itself, and they
are separate from anything the installer does. `sn doctor` reports which ones
are missing.

## The normal ones

Long-press the Termux:API app icon → App info → Permissions, and allow:

| Permission | Needed for |
| --- | --- |
| SMS | `sms_list`, `sms_send` |
| Phone | `call_log`, `call_place` |
| Contacts | `contacts_find`, and resolving a name before sending |
| Camera | `camera_photo` |
| Location | `location_get`, and Wi-Fi details in `network_status` |

Most of these also prompt the first time a tool uses them. If a tool seems to
hang rather than fail, a permission dialog is waiting behind the terminal —
that is what the timeout message in `sn` is telling you.

## Shared storage

```sh
termux-setup-storage
```

Creates `~/storage/shared`, which is where the file tools look. Without it they
can only see Termux's own home directory.

## Notification access

Reading the status bar is a special access, not an ordinary permission:

**Settings → Notifications → Device & app notifications → Termux:API → Allow**

On One UI it is sometimes listed under *Special access → Notification access*.
Without it, `notifications_list` fails; everything else is unaffected.

## Calendar: the one that needs ADB

Android will not show a runtime prompt for `READ_CALENDAR` on Termux, so it has
to be granted over ADB, once. Wireless debugging on the S23 does this from the
phone itself — no computer needed.

1. **Settings → About phone → Software information**, tap *Build number* seven
   times to enable Developer options.
2. **Settings → Developer options → Wireless debugging**, turn it on.
3. Inside it, tap **Pair device with pairing code**. Leave that dialog open —
   it shows a pairing code, an IP address and a *pairing* port.
4. In Termux:

   ```sh
   pkg install android-tools
   adb pair 127.0.0.1:PAIRING_PORT      # the port from the pairing dialog
   # enter the six digit code when asked
   adb connect 127.0.0.1:CONNECT_PORT   # the port on the main wireless debugging screen
   adb shell pm grant com.termux android.permission.READ_CALENDAR
   ```

   The two ports are different, and the connect port changes each time wireless
   debugging is toggled.
5. Check it took:

   ```sh
   sn doctor        # should now show ✓ calendar read
   ```

You can turn wireless debugging back off afterwards; the grant survives, but not
a reinstall of Termux.

**If you would rather not do this**, skip it. `calendar_create_event` works
without any permission at all — it opens the calendar app with the event filled
in for you to save. Only reading events needs the grant. To stop the agent
offering a tool it cannot use:

```toml
[tools]
disabled = ["calendar_list_events"]
```

## Battery optimisation

One UI is aggressive about killing background apps, which will cut a long answer
off mid-generation when the screen goes off:

**Settings → Apps → Termux → Battery → Unrestricted**

The `sn-chat` shortcut also takes a wake lock for the length of the
conversation. You can take one by hand with `termux-wake-lock`, and drop it with
`termux-wake-unlock`.
