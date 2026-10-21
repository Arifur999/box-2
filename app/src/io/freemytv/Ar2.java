package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.SystemClock;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

/**
 * AR2: a tab next to Explore, Movies and Shows that opens one app picked in Home Settings, behind a PIN
 * (ar2_pin in config.properties, kept out of git). The app it opens is hidden from All Apps and the shelf, so the
 * tab is the way in. Five wrong PINs lock it for a minute.
 */
final class Ar2 {
    private Ar2() {}

    static final String NAME = "AR2";
    private static final int MAX_TRIES = 5;
    private static final long LOCKOUT_MS = 60_000;
    private static int wrong;
    private static long lockedUntil;

    static boolean hasPin() {
        return !Config.AR2_PIN.trim().isEmpty();
    }

    /** The package AR2 opens and hides ("" = none picked yet). */
    static String app() {
        return Prefs.ar2App();
    }

    static boolean hides(String pkg) {
        String a = app();
        return !a.isEmpty() && a.equals(pkg);
    }

    /** Asks for the PIN (if there is one), then runs `ok`. */
    static void askPin(Activity a, String title, Runnable ok) {
        if (!hasPin()) {
            ok.run();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < lockedUntil) {
            toast(a, Ui.tr("Muitas tentativas. Espere ", "Too many tries. Wait ") + ((lockedUntil - now) / 1000 + 1)
                    + Ui.tr(" s.", " s."));
            return;
        }
        EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setHint(Ui.tr("Senha", "PIN"));
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        AlertDialog dialog = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(field)
                .setPositiveButton("OK", (d, which) -> check(a, field.getText().toString(), ok))
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                .create();
        field.setOnEditorActionListener((v, action, event) -> {
            dialog.dismiss();
            check(a, field.getText().toString(), ok);
            return true;
        });
        dialog.show();
        field.requestFocus();
    }

    private static void check(Activity a, String typed, Runnable ok) {
        if (typed.trim().equals(Config.AR2_PIN.trim())) {
            wrong = 0;
            ok.run();
            return;
        }
        if (++wrong >= MAX_TRIES) {
            wrong = 0;
            lockedUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS;
            toast(a, Ui.tr("Senha errada. AR2 bloqueado por 1 minuto.", "Wrong PIN. AR2 is locked for 1 minute."));
        } else {
            toast(a, Ui.tr("Senha errada.", "Wrong PIN."));
        }
    }

    /** The tab: PIN, then the app. */
    static void open(Activity a) {
        String pkg = app();
        if (pkg.isEmpty()) {
            toast(a, Ui.tr("Escolha o app do AR2 em Free My TV → Ajustes do Início.",
                    "Pick AR2's app in Free My TV → Home Settings."));
            return;
        }
        askPin(a, NAME, () -> {
            Intent intent = HomeApps.launchIntent(a.getPackageManager(), pkg);
            if (intent == null) {
                toast(a, Ui.tr("O app do AR2 não está instalado.", "AR2's app isn't installed."));
                return;
            }
            try {
                a.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            } catch (RuntimeException e) {
                toast(a, Ui.tr("Não deu para abrir o app do AR2.", "Couldn't open AR2's app."));
            }
        });
    }

    private static void toast(Activity a, String text) {
        Toast.makeText(a, text, Toast.LENGTH_LONG).show();
    }
}
