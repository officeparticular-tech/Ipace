package pt.ipacediag;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import pt.ipacediag.core.DemoCar;
import pt.ipacediag.core.Definitions;
import pt.ipacediag.core.DoIpLink;
import pt.ipacediag.core.DoIpDiscovery;
import pt.ipacediag.core.Procedure;
import pt.ipacediag.core.ProcedureRunner;
import pt.ipacediag.core.Sniffer;
import pt.ipacediag.core.Vehicle;
import pt.ipacediag.core.DemoLeaf;
import pt.ipacediag.core.LeafBattery;
import pt.ipacediag.core.Diagnostics;
import pt.ipacediag.core.Discovery;
import pt.ipacediag.core.Dtc;
import pt.ipacediag.core.DtcCatalog;
import pt.ipacediag.core.ElmSession;
import pt.ipacediag.core.Hex;
import pt.ipacediag.core.IpaceModules;
import pt.ipacediag.core.IpaceParams;
import pt.ipacediag.core.ObdTransport;
import pt.ipacediag.core.Recorder;
import pt.ipacediag.core.Report;
import pt.ipacediag.core.TwelveVolt;
import pt.ipacediag.core.UdsResponse;
import pt.ipacediag.ui.Gauge;
import pt.ipacediag.ui.Legal;
import pt.ipacediag.ui.PdfReport;
import pt.ipacediag.ui.TempBar;
import pt.ipacediag.ui.Theme;

public class MainActivity extends Activity {

    // ---------- estado da aplicação (sobrevive à recriação da atividade) ----------
    static ElmSession session;
    static DemoCar demo;
    static String connectedName = "";
    static final ExecutorService worker = Executors.newSingleThreadExecutor();
    static volatile boolean cancel = false;
    static volatile boolean busy = false;
    static volatile int liveToken = 0;
    static List<Diagnostics.ModuleInfo> lastScan;
    static final Map<String, Double> hv = new LinkedHashMap<>();
    static final Map<String, String> v12 = new LinkedHashMap<>();
    static List<Discovery.Candidate> v12Candidates = new ArrayList<>();
    static final List<Recorder.Extra> extras = new ArrayList<>();
    static final List<String> chargeEvents = new ArrayList<>();
    static String lastChargeFile = "";
    static boolean trafficLog = false;
    static Vehicle vehicle = Vehicle.IPACE;
    static boolean demoMode = false;
    static LeafBattery leafLast;
    static Definitions defs;
    static List<Sniffer.Event> lastCapture = new ArrayList<>();
    static String lastCaptureVin = "";
    private static final int REQ_IMPORT = 11;
    static Storage.LineFile trafficFile;

    private static final int REQ_BT = 7;
    private static final int REQ_BLE = 8;
    private String[] tabLabels() {
        return vehicle == Vehicle.LEAF ? new String[]{"Bateria", "Mais"} : new String[]{"Início", "Erros", "Bateria", "12V", "Registo", "Mais"};
    }
    private String[] tabIcons() {
        return vehicle == Vehicle.LEAF ? new String[]{"⚡", "⋯"} : new String[]{"◉", "⚠", "⚡", "▮", "●", "⋯"};
    }
    private int moreTab() { return vehicle == Vehicle.LEAF ? 1 : 5; }

    private LinearLayout content;
    private LinearLayout navBar;
    private TextView carChip;
    private TextView statusPill, stepText;
    private Button stopBtn;
    private final List<TextView> tabViews = new ArrayList<>();
    private int currentTab = 0;

    // ---------- ciclo de vida ----------
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Theme.BG);
        getWindow().setNavigationBarColor(Theme.SURFACE);
        vehicle = Vehicle.byId(prefs().getString("vehicle", "ipace"));
        buildShell();
        if (defs == null) loadDefinitions(false);
        if (!termsAccepted()) showTerms(true);
        else showTab(0);
    }

    private boolean termsAccepted() {
        return Legal.VERSION.equals(prefs().getString("terms", ""));
    }

    private SharedPreferences prefs() { return getSharedPreferences("ipace", MODE_PRIVATE); }

    // ---------- estrutura ----------
    private void buildShell() {
        Context c = this;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG);
        root.setFitsSystemWindows(true);

        LinearLayout header = Theme.row(c);
        header.setPadding(Theme.dp(c, 20), Theme.dp(c, 18), Theme.dp(c, 16), Theme.dp(c, 6));
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t1 = Theme.text(c, "I-Pace", 28, Theme.GOLD, true);
        t1.setLetterSpacing(0.04f);
        TextView t2 = Theme.text(c, "HUGO VALVERDE · PT", 11, Theme.MUTED, true);
        t2.setLetterSpacing(0.25f);
        titles.addView(t1);
        titles.addView(t2);
        carChip = Theme.pill(c, vehicle.name, Theme.TEAL);
        carChip.setPadding(Theme.dp(c, 12), Theme.dp(c, 6), Theme.dp(c, 12), Theme.dp(c, 6));
        LinearLayout.LayoutParams ccp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ccp.rightMargin = Theme.dp(c, 8);
        carChip.setLayoutParams(ccp);
        carChip.setOnClickListener(v -> { if (termsAccepted()) screenCarPicker(); });
        statusPill = Theme.pill(c, "DESLIGADO", Theme.MUTED);
        header.addView(titles);
        header.addView(carChip);
        header.addView(statusPill);
        titles.setOnClickListener(v -> { if (termsAccepted()) screenCarPicker(); });
        root.addView(header);

        LinearLayout stepRow = Theme.row(c);
        stepRow.setPadding(Theme.dp(c, 20), 0, Theme.dp(c, 16), Theme.dp(c, 4));
        stepText = Theme.muted(c, "");
        stepText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stopBtn = Theme.danger(c, "Parar");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Theme.dp(c, 40));
        stopBtn.setLayoutParams(sp);
        stopBtn.setMinHeight(0);
        stopBtn.setPadding(Theme.dp(c, 16), 0, Theme.dp(c, 16), 0);
        stopBtn.setVisibility(View.GONE);
        stopBtn.setOnClickListener(v -> { cancel = true; liveToken++; stepText.setText("A parar…"); });
        stepRow.addView(stepText);
        stepRow.addView(stopBtn);
        root.addView(stepRow);

        ScrollView scroll = new ScrollView(c);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setFillViewport(true);
        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Theme.dp(c, 16), 0, Theme.dp(c, 16), Theme.dp(c, 24));
        scroll.addView(content);
        root.addView(scroll);

        navBar = Theme.row(c);
        navBar.setBackgroundColor(Theme.SURFACE);
        navBar.setPadding(0, Theme.dp(c, 6), 0, Theme.dp(c, 8));
        populateNav();
        root.addView(navBar);
        setContentView(root);
        refreshStatus();
    }

    private void populateNav() {
        Context c = this;
        navBar.removeAllViews();
        tabViews.clear();
        String[] labels = tabLabels(), icons = tabIcons();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView tv = Theme.text(c, icons[i] + "\n" + labels[i], 11, Theme.MUTED, true);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, Theme.dp(c, 4), 0, Theme.dp(c, 4));
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tv.setOnClickListener(v -> { if (termsAccepted()) showTab(idx); });
            tabViews.add(tv);
            navBar.addView(tv);
        }
    }

    private void refreshStatus() {
        if (carChip != null) carChip.setText(vehicle.name);
        if (session == null) setPill("DESLIGADO", Theme.MUTED);
        else if (demoMode) setPill("DEMONSTRAÇÃO", Theme.WARN);
        else setPill("LIGADO", Theme.OK);
    }

    private void setPill(String s, int color) {
        statusPill.setText(s);
        statusPill.setTextColor(color);
        statusPill.setBackground(Theme.round(this, (color & 0x00FFFFFF) | 0x26000000, (color & 0x00FFFFFF) | 0x66000000, 999));
    }

    private void showTab(int i) {
        liveToken++;
        currentTab = i;
        for (int k = 0; k < tabViews.size(); k++) tabViews.get(k).setTextColor(k == i ? Theme.GOLD : Theme.MUTED);
        content.removeAllViews();
        if (vehicle == Vehicle.LEAF) {
            if (i == 0) screenLeafBattery(); else screenMore();
        } else {
            switch (i) {
                case 0: screenHome(); break;
                case 1: screenErrors(); break;
                case 2: screenHv(); break;
                case 3: screen12v(); break;
                case 4: screenCharge(); break;
                default: screenMore(); break;
            }
        }
        if (getWindow().getDecorView() != null) content.post(() -> ((ScrollView) content.getParent()).scrollTo(0, 0));
    }

    // ---------- utilitários ----------
    interface Task { void run() throws Exception; }

    private void ui(Runnable r) { runOnUiThread(r); }

    private void toast(String s) { ui(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }

    private void step(String s) { ui(() -> stepText.setText(s)); }

    private final Diagnostics.Progress progress = new Diagnostics.Progress() {
        public void step(String m) { MainActivity.this.step(m); }
        public boolean cancelled() { return cancel; }
    };

    private boolean needConn() {
        if (session != null) return true;
        toast("Primeiro ligue ao adaptador ou abra o modo demonstração (separador Início).");
        showTab(0);
        return false;
    }

    static volatile boolean currentIsLive = false;

    /** Executa uma tarefa no fio de comunicação, com indicação de progresso e botão Parar. */
    private void run(String label, boolean stoppable, Task t) {
        if (busy && !currentIsLive) { toast("Há uma tarefa em curso — toque em Parar primeiro."); return; }
        liveToken++; // termina qualquer leitura ao vivo
        exec(label, stoppable, false, t);
    }

    /** Leitura contínua: termina quando liveToken muda (mudança de separador ou nova tarefa). */
    private void runLive(String label, Task t) {
        if (busy && !currentIsLive) { toast("Há uma tarefa em curso — toque em Parar primeiro."); return; }
        exec(label, !label.isEmpty(), true, t);
    }

    private void exec(String label, boolean stoppable, boolean live, Task t) {
        worker.execute(() -> {
            busy = true;
            currentIsLive = live;
            cancel = false;
            ui(() -> {
                stepText.setText(label);
                stopBtn.setVisibility(stoppable ? View.VISIBLE : View.GONE);
            });
            try {
                t.run();
            } catch (Exception e) {
                toast("Erro: " + e.getMessage());
            } finally {
                busy = false;
                currentIsLive = false;
                ui(() -> {
                    stopBtn.setVisibility(View.GONE);
                    stepText.setText("");
                });
            }
        });
    }

    private LinearLayout add(View v) { content.addView(v); return content; }

    private LinearLayout card(String title, String subtitle) {
        LinearLayout c = Theme.card(this);
        if (title != null) c.addView(Theme.title(this, title));
        if (subtitle != null) {
            TextView s = Theme.muted(this, subtitle);
            s.setPadding(0, Theme.dp(this, 4), 0, 0);
            c.addView(s);
        }
        content.addView(c);
        return c;
    }

    private Button btn(LinearLayout parent, String s, boolean primary, View.OnClickListener l) {
        Button b = Theme.button(this, s, primary);
        b.setOnClickListener(l);
        parent.addView(b);
        return b;
    }

    private static String fmt(double v, int dec) {
        return Double.isNaN(v) ? "—" : String.format(Locale.US, "%." + dec + "f", v);
    }

    private static int v12Color(double v) {
        if (Double.isNaN(v)) return Theme.MUTED;
        if (v >= 13.2) return Theme.TEAL;
        if (v >= 12.4) return Theme.OK;
        if (v >= 12.0) return Theme.WARN;
        return Theme.BAD;
    }

    private void onDemoState(DemoCar.State s) {
        if (demo != null) demo.setState(s);
    }

    // ---------- termos ----------
    private void showTerms(boolean mustAccept) {
        content.removeAllViews();
        LinearLayout c = card(Legal.TITLE, mustAccept ? "Obrigatório antes da primeira utilização" : null);
        TextView body = Theme.body(this, Legal.TEXT);
        body.setPadding(0, Theme.dp(this, 12), 0, 0);
        c.addView(body);
        if (mustAccept) {
            CheckBox cb = new CheckBox(this);
            cb.setText("Li e compreendi. Assumo integralmente todos os riscos e responsabilidades, mesmo em caso de erros de programação não detetados.");
            cb.setTextColor(Theme.TEXT);
            cb.setPadding(0, Theme.dp(this, 12), 0, 0);
            c.addView(cb);
            Button ok = btn(c, "Aceito e assumo a responsabilidade", true, v -> {});
            ok.setEnabled(false);
            ok.setAlpha(0.4f);
            cb.setOnCheckedChangeListener((x, on) -> { ok.setEnabled(on); ok.setAlpha(on ? 1f : 0.4f); });
            ok.setOnClickListener(v -> {
                prefs().edit().putString("terms", Legal.VERSION).putLong("terms_at", System.currentTimeMillis()).apply();
                showTab(0);
            });
            btn(c, "Não aceito — sair", false, v -> finish());
        }
    }

    // =====================================================================
    // INÍCIO
    // =====================================================================
    private void screenHome() {
        if (session == null) {
            LinearLayout hero = card("Bem-vindo", "Diagnóstico do Jaguar I-Pace (2018) pela tomada OBD com um adaptador Bluetooth OBDLink MX+.");
            btn(hero, "Ligar (Bluetooth clássico: MX+, LX, vLinker)", true, v -> connectFlow());
            btn(hero, "Ligar (Bluetooth LE: CX, Veepeak)", false, v -> connectFlowBle());
            btn(hero, "Ligar (OBD WiFi)", false, v -> connectFlowWifi());
            btn(hero, "Ligar (DoIP / Ethernet — avançado)", false, v -> connectFlowDoip());
            btn(hero, "Explorar em modo demonstração", false, v -> startDemo());
            LinearLayout how = card("Antes de ligar", null);
            how.addView(Theme.body(this,
                    "1. Emparelhe o OBDLink MX+ nas definições Bluetooth do Android (botão de emparelhamento no adaptador).\n"
                    + "2. Ligue o adaptador à tomada OBD, por baixo do tablier, do lado do condutor.\n"
                    + "3. Abra as portas ou ligue o carro para acordar os módulos.\n"
                    + "4. No fim, retire o adaptador: deixá-lo ligado gasta a bateria de 12V."));
            return;
        }
        if (demo != null) demoCard();

        LinearLayout gauges = card(null, null);
        LinearLayout row = Theme.row(this);
        Gauge soc = new Gauge(this, "Carga AT", "%", 0, 100, 0, x -> x < 20 ? Theme.WARN : Theme.TEAL);
        Gauge g12 = new Gauge(this, "Bateria 12V", "V", 10.5, 15, 2, MainActivity::v12Color);
        soc.setLayoutParams(Theme.weight(this, 0));
        g12.setLayoutParams(Theme.weight(this, 8));
        row.addView(soc);
        row.addView(g12);
        gauges.addView(row);
        TextView live = Theme.muted(this, "A ler…");
        live.setGravity(Gravity.CENTER);
        gauges.addView(live);

        LinearLayout quick = card("Resumo", null);
        LinearLayout kvBox = new LinearLayout(this);
        kvBox.setOrientation(LinearLayout.VERTICAL);
        quick.addView(kvBox);
        btn(quick, "Diagnóstico completo (todos os módulos)", true, v -> { showTab(1); fullScan(); });
        btn(quick, "Desligar", false, v -> disconnect());

        final int token = ++liveToken;
        final long liveStart = System.currentTimeMillis();
        runLive("", () -> {
            while (liveToken == token && !cancel && session != null) {
                if (demo == null && System.currentTimeMillis() - liveStart > 5 * 60_000L) {
                    ui(() -> live.setText("Leitura ao vivo em pausa (5 min) para deixar o carro adormecer. Toque em Início para retomar."));
                    break;
                }
                double v = session.voltage();
                IpaceParams.Param ps = IpaceParams.get("soc");
                double s = ps.decodeSafe(session.readDid(ps.tx, ps.did));
                double soh = IpaceParams.get("soh_cap").decodeSafe(session.readDid(IpaceModules.BECM, 0x4918));
                double cmax = IpaceParams.get("cell_max").decodeSafe(session.readDid(IpaceModules.BECM, 0x4903));
                double cmin = IpaceParams.get("cell_min").decodeSafe(session.readDid(IpaceModules.BECM, 0x4904));
                ui(() -> {
                    soc.setValue(s);
                    g12.setValue(v);
                    live.setText(Double.isNaN(s) ? "BECM sem resposta — acorde o carro (abrir portas / ligar)" : "Atualizado " + new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date()));
                    kvBox.removeAllViews();
                    kvBox.addView(Theme.kv(this, "Saúde da bateria AT (capacidade)", fmt(soh, 1) + " %", Double.isNaN(soh) ? Theme.MUTED : soh >= 80 ? Theme.OK : soh >= 70 ? Theme.WARN : Theme.BAD));
                    double d = (cmax - cmin) * 1000;
                    kvBox.addView(Theme.kv(this, "Diferença entre células", fmt(d, 0) + " mV", Double.isNaN(d) ? Theme.MUTED : d < 30 ? Theme.OK : d < 60 ? Theme.WARN : Theme.BAD));
                    int n = 0;
                    if (lastScan != null) for (Diagnostics.ModuleInfo mi : lastScan) n += mi.dtcs.size();
                    kvBox.addView(Theme.kv(this, "Erros no último diagnóstico", lastScan == null ? "por ler" : String.valueOf(n), lastScan == null ? Theme.MUTED : n == 0 ? Theme.OK : Theme.WARN));
                    kvBox.addView(Theme.kv(this, "Adaptador", connectedName, Theme.TEXT));
                });
                for (int i = 0; i < 20 && liveToken == token; i++) Thread.sleep(100);
            }
        });
    }

    private void demoCard() {
        LinearLayout c = card("Modo demonstração", "Dados SIMULADOS. Mude o estado do carro virtual para ver como a app reage.");
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Theme.row(this);
        for (DemoCar.State s : DemoCar.State.values()) {
            boolean on = demo.state() == s;
            TextView chip = Theme.pill(this, s.label, on ? Theme.GOLD : Theme.MUTED);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Theme.dp(this, 8);
            lp.topMargin = Theme.dp(this, 12);
            chip.setLayoutParams(lp);
            chip.setPadding(Theme.dp(this, 14), Theme.dp(this, 8), Theme.dp(this, 14), Theme.dp(this, 8));
            chip.setOnClickListener(v -> { onDemoState(s); showTab(currentTab); });
            chips.addView(chip);
        }
        hs.addView(chips);
        c.addView(hs);
    }

    // ---------- ligação ----------
    private void connectFlow() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            return;
        }
        pickDevice();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_BT && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) pickDevice();
        else if (code == REQ_BLE) {
            boolean ok = res.length > 0;
            for (int x : res) if (x != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) scanBle(); else toast("Sem a permissão de Bluetooth a app não consegue procurar adaptadores LE.");
        } else toast("Sem a permissão de Bluetooth a app não consegue falar com o adaptador.");
    }

    @SuppressLint("MissingPermission")
    private void pickDevice() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) { toast("Este telemóvel não tem Bluetooth."); return; }
        if (!ad.isEnabled()) { toast("Ligue o Bluetooth e tente de novo."); return; }
        Set<BluetoothDevice> bonded = ad.getBondedDevices();
        List<BluetoothDevice> list = new ArrayList<>();
        for (BluetoothDevice d : bonded) {
            String n = d.getName() == null ? "" : d.getName().toUpperCase(Locale.ROOT);
            if (n.contains("OBD") || n.contains("MX") || n.contains("LINK") || n.contains("VLINK") || n.contains("ELM")) list.add(0, d);
            else list.add(d);
        }
        if (list.isEmpty()) { toast("Nenhum aparelho emparelhado. Emparelhe primeiro o OBDLink MX+ nas definições Bluetooth."); return; }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = (list.get(i).getName() == null ? "?" : list.get(i).getName()) + "  ·  " + list.get(i).getAddress();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Escolha o adaptador")
                .setItems(names, (dlg, i) -> connect(list.get(i), names[i]))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void connect(BluetoothDevice dev, String name) {
        run("A ligar a " + name + "…", false, () -> {
            ObdTransport t = new BtTransport(dev);
            open(t, name.split("  ·  ")[0], null);
        });
    }


    // ---------- ligação Bluetooth LE ----------
    private void connectFlowBle() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!need.isEmpty()) { requestPermissions(need.toArray(new String[0]), REQ_BLE); return; }
        scanBle();
    }

    @SuppressLint("MissingPermission")
    private void scanBle() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { toast("Ligue o Bluetooth e tente de novo."); return; }
        BluetoothLeScanner scanner = ad.getBluetoothLeScanner();
        if (scanner == null) { toast("Este telemóvel não tem Bluetooth LE disponível."); return; }
        final Map<String, BluetoothDevice> found = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, String> names = new java.util.concurrent.ConcurrentHashMap<>();
        ScanCallback sc = new ScanCallback() {
            @SuppressLint("MissingPermission")
            @Override public void onScanResult(int type, ScanResult r) {
                BluetoothDevice d = r.getDevice();
                found.put(d.getAddress(), d);
                String n = d.getName();
                if ((n == null || n.isEmpty()) && r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
                names.put(d.getAddress(), n == null ? "(sem nome)" : n);
            }
        };
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("A procurar adaptadores Bluetooth LE…")
                .setMessage("8 segundos. Ligue o adaptador à tomada OBD e acorde o carro.")
                .setCancelable(false).create();
        dlg.show();
        scanner.startScan(sc);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { scanner.stopScan(sc); } catch (Exception ignored) { }
            dlg.dismiss();
            if (found.isEmpty()) { toast("Nenhum adaptador BLE encontrado. Confirme que está ligado à tomada OBD."); return; }
            List<BluetoothDevice> list = new ArrayList<>(found.values());
            list.sort((a, b) -> rank(names.get(b.getAddress())) - rank(names.get(a.getAddress())));
            String[] labels = new String[list.size()];
            for (int i = 0; i < list.size(); i++) labels[i] = names.get(list.get(i).getAddress()) + "  ·  " + list.get(i).getAddress();
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Escolha o adaptador BLE")
                    .setItems(labels, (d, i) -> connectBle(list.get(i), labels[i]))
                    .setNegativeButton("Cancelar", null).show();
        }, 8000);
    }

    private static int rank(String name) {
        if (name == null) return 0;
        String n = name.toUpperCase(Locale.ROOT);
        return (n.contains("OBD") || n.contains("CX") || n.contains("VEEPEAK") || n.contains("VLINK") || n.contains("ELM")) ? 1 : 0;
    }

    private void connectBle(BluetoothDevice dev, String label) {
        run("A ligar por BLE…", false, () -> {
            ObdTransport t = new BleTransport(this, dev);
            open(t, label.split("  ·  ")[0], null);
        });
    }

    private void startDemo() {
        run("A abrir o modo demonstração…", false, () -> {
            if (vehicle == Vehicle.LEAF) open(new DemoLeaf(), "Simulador Leaf", null);
            else { DemoCar car = new DemoCar(); open(car, "Simulador", car); }
        });
    }

    // ---------- ligação WiFi ----------
    private void connectFlowWifi() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Theme.dp(this, 20);
        box.setPadding(pad, Theme.dp(this, 8), pad, 0);
        EditText ip = Theme.input(this, "IP do adaptador", false);
        ip.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        ip.setText(prefs().getString("wifi_ip", "192.168.0.10"));
        EditText port = Theme.input(this, "Porta", true);
        port.setText(prefs().getString("wifi_port", "35000"));
        box.addView(Theme.muted(this, "Ligue primeiro o telemóvel à rede WiFi do adaptador (Definições → WiFi). Valores habituais já preenchidos."));
        box.addView(ip);
        box.addView(port);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Adaptador OBD WiFi")
                .setView(box)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Ligar", (d, w) -> {
                    String host = ip.getText().toString().trim();
                    int p;
                    try { p = Integer.parseInt(port.getText().toString().trim()); } catch (Exception e) { toast("Porta inválida."); return; }
                    prefs().edit().putString("wifi_ip", host).putString("wifi_port", String.valueOf(p)).apply();
                    run("A ligar por WiFi a " + host + "…", false, () -> {
                        ObdTransport t = new WifiTransport(host, p);
                        open(t, "WiFi " + host, null);
                    });
                }).show();
    }

    // ---------- ligação DoIP / Ethernet (avançado) ----------
    private void connectFlowDoip() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Theme.dp(this, 20);
        box.setPadding(pad, Theme.dp(this, 8), pad, 0);

        box.addView(Theme.muted(this,
                "Avançado. Requer o telemóvel/portátil na rede Ethernet do carro (conversor 100BASE-T1 + linha de ativação). "
                + "O DoIP não contorna a segurança JLR: só leitura e apagar erros. Fala com UM módulo (endereço de destino). "
                + "Pode nem existir na tomada do I-Pace de 2018."));

        EditText ip = Theme.input(this, "IP da entidade (gateway)", false);
        ip.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        ip.setText(prefs().getString("doip_ip", "169.254.100.100"));
        EditText port = Theme.input(this, "Porta", true);
        port.setText(prefs().getString("doip_port", String.valueOf(pt.ipacediag.core.DoIp.PORT)));
        EditText src = Theme.input(this, "Endereço do tester (hex)", false);
        src.setText(prefs().getString("doip_src", "0E80"));
        EditText tgt = Theme.input(this, "Endereço do módulo/destino (hex)", false);
        tgt.setText(prefs().getString("doip_tgt", "1000"));
        box.addView(ip); box.addView(port); box.addView(src); box.addView(tgt);

        btn(box, "Procurar carro na rede (UDP)", false, v -> worker.execute(() -> {
            try {
                java.util.List<DoIpDiscovery.Found> found = DoIpDiscovery.discover(1500);
                ui(() -> {
                    if (found.isEmpty()) { toast("Nenhuma entidade DoIP respondeu."); return; }
                    DoIpDiscovery.Found f0 = found.get(0);
                    ip.setText(f0.host);
                    tgt.setText(Integer.toHexString(f0.logicalAddress).toUpperCase());
                    toast("Encontrado: " + f0.vin + " @ " + f0.host);
                });
            } catch (Exception e) {
                ui(() -> toast("Falha na procura: " + e.getMessage()));
            }
        }));

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Ligação DoIP (Ethernet)")
                .setView(box)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Ligar", (d, w) -> {
                    String host = ip.getText().toString().trim();
                    int p, source, targetAddr;
                    try { p = Integer.parseInt(port.getText().toString().trim()); } catch (Exception e) { toast("Porta inválida."); return; }
                    try { source = Integer.parseInt(src.getText().toString().trim().replace("0x", ""), 16); } catch (Exception e) { toast("Endereço do tester inválido."); return; }
                    try { targetAddr = Integer.parseInt(tgt.getText().toString().trim().replace("0x", ""), 16); } catch (Exception e) { toast("Endereço de destino inválido."); return; }
                    prefs().edit().putString("doip_ip", host).putString("doip_port", String.valueOf(p))
                            .putString("doip_src", Integer.toHexString(source)).putString("doip_tgt", Integer.toHexString(targetAddr)).apply();
                    run("A ligar por DoIP a " + host + "…", false, () ->
                            openDoip(new DoIpLink(host, p, source, targetAddr), "DoIP " + host));
                }).show();
    }

    // ---------- seleção de carro ----------
    private void screenCarPicker() {
        content.removeAllViews();
        LinearLayout c = card("Escolher carro", "A app adapta-se ao carro selecionado. Pode trocar a qualquer momento no cabeçalho.");
        for (Vehicle vv : Vehicle.values()) {
            LinearLayout row = Theme.card(this);
            row.setBackground(Theme.round(this, vv == vehicle ? Theme.SURFACE_2 : Theme.SURFACE, vv == vehicle ? Theme.GOLD : Theme.STROKE, 16));
            LinearLayout head = Theme.row(this);
            TextView name = Theme.title(this, vv.name);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(name);
            if (vv == vehicle) head.addView(Theme.pill(this, "ATUAL", Theme.GOLD));
            row.addView(head);
            row.addView(Theme.muted(this, vv.subtitle));
            row.addView(Theme.body(this, vv.description));
            row.setOnClickListener(v -> selectVehicle(vv));
            content.addView(row);
        }
        content.addView(Theme.muted(this, "\nNota: o I-Pace tem diagnóstico completo e foi testado contra um simulador. O Leaf traz o núcleo da bateria, com valores por confirmar no carro."));
    }

    private void selectVehicle(Vehicle vv) {
        if (vv == vehicle && session != null) { showTab(0); return; }
        Runnable apply = () -> {
            vehicle = vv;
            prefs().edit().putString("vehicle", vv.id).apply();
            populateNav();
            refreshStatus();
            showTab(0);
        };
        if (session != null) {
            liveToken++; cancel = true;
            worker.execute(() -> {
                if (session != null) session.close();
                session = null; demo = null; demoMode = false;
                ui(apply);
            });
        } else apply.run();
    }

    private void open(ObdTransport t, String name, DemoCar car) throws Exception {
        ElmSession s = new ElmSession(t);
        if (trafficLog) {
            trafficFile = new Storage.LineFile(this, "trafego_" + Storage.stamp() + ".txt");
            s.setTrafficLog((cmd, resp) -> trafficFile.line(System.currentTimeMillis() + " > " + cmd + " | " + resp.replace("\r", " / ")));
        }
        finishOpen(s, name, car);
    }

    private void openDoip(DoIpLink link, String name) throws Exception {
        finishOpen(new ElmSession(link), name, null);
    }

    private void finishOpen(ElmSession s, String name, DemoCar car) throws Exception {
        String id = s.init();
        double v = s.voltage();
        session = s;
        demo = car;
        loadDefinitions(demoMode && vehicle == Vehicle.IPACE);
        connectedName = name;
        lastScan = null;
        hv.clear(); v12.clear(); v12Candidates.clear(); chargeEvents.clear();
        ui(() -> {
            refreshStatus();
            toast("Ligado: " + id + (Double.isNaN(v) ? "" : " · " + fmt(v, 1) + " V"));
            showTab(0);
        });
    }

    private void disconnect() {
        liveToken++;
        cancel = true;
        worker.execute(() -> {
            if (session != null) session.close();
            session = null;
            demo = null;
            loadDefinitions(false);
            if (trafficFile != null) { trafficFile.close(); trafficFile = null; }
            ui(() -> { refreshStatus(); showTab(0); });
        });
    }

    // =====================================================================
    // ERROS
    // =====================================================================
    private void screenErrors() {
        LinearLayout top = card("Códigos de erro", "Lê os erros de todos os módulos (cerca de 1 minuto). Só leitura.");
        btn(top, lastScan == null ? "Ler erros de todos os módulos" : "Ler novamente", true, v -> fullScan());
        if (lastScan != null) renderScan();
    }

    private void fullScan() {
        if (!needConn()) return;
        run("A procurar módulos…", true, () -> {
            Diagnostics d = new Diagnostics(session);
            List<Diagnostics.ModuleInfo> mods = d.scanModules(progress);
            d.readAllDtcs(mods, progress);
            lastScan = mods;
            ui(() -> { if (currentTab == 1) showTab(1); });
        });
    }

    private void renderScan() {
        int present = 0, total = 0, lowV = 0;
        boolean hvCodes = false;
        List<Diagnostics.ModuleInfo> withErr = new ArrayList<>();
        StringBuilder clean = new StringBuilder();
        for (Diagnostics.ModuleInfo mi : lastScan) {
            if (!mi.responded) continue;
            present++;
            if (mi.dtcs.isEmpty()) { clean.append(mi.module.code).append("  "); continue; }
            withErr.add(mi);
            for (Dtc d : mi.dtcs) {
                total++;
                if (DtcCatalog.isLowVoltageEvent(d)) lowV++;
                if (DtcCatalog.isHighVoltage(d)) hvCodes = true;
            }
        }
        LinearLayout sum = card(null, null);
        LinearLayout r = Theme.row(this);
        TextView big = Theme.text(this, String.valueOf(total), 40, total == 0 ? Theme.OK : Theme.WARN, true);
        TextView lbl = Theme.body(this, "  erro(s) em " + withErr.size() + " módulo(s)\n  " + present + " módulos responderam");
        r.addView(big);
        r.addView(lbl);
        sum.addView(r);
        if (lowV >= 2) {
            TextView w = Theme.text(this, "⚠ " + lowV + " módulos registaram tensão 12V anormal (U3003). Típico de uma queda da bateria de 12V — veja o separador 12V antes de culpar a bateria de alta tensão.", 14, Theme.WARN, false);
            w.setPadding(0, Theme.dp(this, 10), 0, 0);
            sum.addView(w);
        }
        if (hvCodes) {
            TextView w = Theme.text(this, "⚡ Há erros de ALTA TENSÃO. Guarde o relatório antes de apagar: é prova do problema.", 14, Theme.BAD, false);
            w.setPadding(0, Theme.dp(this, 8), 0, 0);
            sum.addView(w);
        }

        for (Diagnostics.ModuleInfo mi : withErr) {
            LinearLayout c = card(null, null);
            LinearLayout head = Theme.row(this);
            TextView name = Theme.title(this, mi.module.code);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(name);
            head.addView(Theme.pill(this, mi.dtcs.size() + (mi.dtcs.size() == 1 ? " ERRO" : " ERROS"), Theme.WARN));
            c.addView(head);
            c.addView(Theme.muted(this, mi.module.name + " · " + Hex.h3(mi.module.tx)));
            for (Dtc d : mi.dtcs) {
                c.addView(spacer(10));
                c.addView(Theme.divider(this));
                c.addView(spacer(10));
                LinearLayout dr = Theme.row(this);
                TextView code = Theme.text(this, d.code(), 17, Theme.TEXT, true);
                code.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                dr.addView(code);
                int col = d.testFailed() ? Theme.BAD : d.confirmed() ? Theme.WARN : Theme.MUTED;
                dr.addView(Theme.pill(this, d.testFailed() ? "ATIVO" : d.confirmed() ? "CONFIRMADO" : d.pending() ? "PENDENTE" : "HISTÓRICO", col));
                c.addView(dr);
                c.addView(Theme.body(this, DtcCatalog.describe(d)));
                c.addView(Theme.muted(this, d.statusText()));
            }
            Button det = btn(c, "Ver dados do momento do erro", false, v -> dtcDetails(mi));
            det.setTag(mi);
            Button clr = Theme.danger(this, "Apagar erros do " + mi.module.code);
            clr.setOnClickListener(v -> confirmClear(java.util.Collections.singletonList(mi)));
            c.addView(clr);
        }
        if (clean.length() > 0) {
            LinearLayout c = card("Sem erros", null);
            c.addView(Theme.muted(this, clean.toString().trim()));
        }
        LinearLayout act = card("Ações", null);
        btn(act, "Guardar relatório (PDF + texto)", true, v -> saveReport(false));
        if (!withErr.isEmpty()) {
            Button all = Theme.danger(this, "Apagar erros de TODOS os módulos");
            all.setOnClickListener(v -> confirmClear(withErr));
            act.addView(all);
        }
    }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Theme.dp(this, dp)));
        return v;
    }

    private void dtcDetails(Diagnostics.ModuleInfo mi) {
        run("A ler dados do momento do erro…", false, () -> {
            Diagnostics d = new Diagnostics(session);
            StringBuilder sb = new StringBuilder();
            for (Dtc x : mi.dtcs) {
                Map<String, String> m = d.readDtcDetail(x);
                sb.append(x.code()).append("\n  Snapshot: ").append(m.get("snapshot"))
                        .append("\n  Dados estendidos: ").append(m.get("extended")).append("\n\n");
            }
            sb.append("Valores em bruto (hexadecimal). O significado exato de cada campo é definido pela JLR; o relatório guarda-os tal como estão para análise posterior.");
            String txt = sb.toString();
            ui(() -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(mi.module.code + " — dados do erro").setMessage(txt).setPositiveButton("OK", null).show());
        });
    }

    private void confirmClear(List<Diagnostics.ModuleInfo> targets) {
        boolean hvCodes = false;
        StringBuilder codes = new StringBuilder();
        for (Diagnostics.ModuleInfo mi : targets) for (Dtc d : mi.dtcs) {
            codes.append(mi.module.code).append(": ").append(d.code()).append('\n');
            if (DtcCatalog.isHighVoltage(d)) hvCodes = true;
        }
        final boolean hvF = hvCodes;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Apagar erros?")
                .setMessage("Vão ser apagados:\n" + codes + "\nAntes de apagar, a app guarda automaticamente um relatório completo (com os dados do momento de cada erro) em Transferências/IPaceDiag.\n\n"
                        + "Apagar NÃO repara a avaria: se a causa persistir, o erro volta. A decisão e as consequências são da sua responsabilidade.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Continuar", (d1, w1) -> {
                    if (!hvF) { doClear(targets); return; }
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("⚡ Erros de alta tensão")
                            .setMessage("Inclui erros do sistema de ALTA TENSÃO (ex.: P0AA6 — falha de isolamento). "
                                    + "Uma falha de isolamento é um risco de segurança, e o registo do erro pode ser prova importante para garantia ou recall. "
                                    + "Tem a certeza de que quer apagar?")
                            .setNegativeButton("Não apagar", null)
                            .setPositiveButton("Apagar mesmo assim", (d2, w2) -> doClear(targets))
                            .show();
                })
                .show();
    }

    private void doClear(List<Diagnostics.ModuleInfo> targets) {
        run("A guardar relatório antes de apagar…", false, () -> {
            Diagnostics d = new Diagnostics(session);
            Report r = new Report("RELATÓRIO ANTES DE APAGAR ERROS — i-Pace" + (demo != null ? " [DADOS SIMULADOS]" : ""));
            r.modules(lastScan);
            r.section("Dados do momento de cada erro (bruto)");
            for (Diagnostics.ModuleInfo mi : targets) for (Dtc x : mi.dtcs) {
                Map<String, String> m = d.readDtcDetail(x);
                r.line(mi.module.code + " " + x.code() + " | snapshot: " + m.get("snapshot") + " | estendidos: " + m.get("extended"));
            }
            r.section("Aviso").line(Legal.SHORT);
            String st = Storage.stamp();
            String p1 = Storage.saveText(this, "antes_de_apagar_" + st + ".txt", r.text());
            saveGraphicalPdf("Relatório antes de apagar erros", "antes_de_apagar_" + st + ".pdf");
            StringBuilder res = new StringBuilder("Relatório: " + p1 + "\n\n");
            for (Diagnostics.ModuleInfo mi : targets) {
                step("A apagar erros de " + mi.module.code + "…");
                UdsResponse u = d.clearDtcs(mi.module.tx);
                res.append(mi.module.code).append(": ").append(u.ok() ? "apagados" : u.describe()).append('\n');
                try { mi.dtcs = d.readDtcs(mi.module.tx); } catch (Exception e) { mi.dtcError = e.getMessage(); }
                if (!mi.dtcs.isEmpty()) res.append("   voltaram ").append(mi.dtcs.size()).append(" erro(s) — a causa continua presente\n");
            }
            String msg = res.toString();
            ui(() -> {
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Resultado").setMessage(msg).setPositiveButton("OK", null).show();
                showTab(1);
            });
        });
    }

    // =====================================================================
    // BATERIA DE ALTA TENSÃO
    // =====================================================================
    private void screenHv() {
        LinearLayout top = card("Bateria de alta tensão", "90 kWh · 36 módulos · 432 células. Valores lidos ao BECM.");
        LinearLayout row = Theme.row(this);
        Gauge soc = new Gauge(this, "Carga", "%", 0, 100, 1, x -> x < 20 ? Theme.WARN : Theme.TEAL);
        Gauge soh = new Gauge(this, "Saúde (capacidade)", "%", 50, 100, 1, x -> x >= 80 ? Theme.OK : x >= 70 ? Theme.WARN : Theme.BAD);
        soc.setLayoutParams(Theme.weight(this, 0));
        soh.setLayoutParams(Theme.weight(this, 8));
        row.addView(soc);
        row.addView(soh);
        top.addView(row);
        soc.setValue(hv.getOrDefault("soc", Double.NaN));
        soh.setValue(hv.getOrDefault("soh_cap", Double.NaN));
        LinearLayout btns = Theme.row(this);
        Button read = Theme.button(this, "Ler valores", true);
        Button live = Theme.button(this, "Ao vivo", false);
        read.setLayoutParams(Theme.weight(this, 0));
        live.setLayoutParams(Theme.weight(this, 8));
        btns.addView(read);
        btns.addView(live);
        top.addView(btns);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);
        renderHv(list);

        read.setOnClickListener(v -> {
            if (!needConn()) return;
            run("A ler a bateria…", true, () -> {
                readHv(true);
                ui(() -> { soc.setValue(hv.getOrDefault("soc", Double.NaN)); soh.setValue(hv.getOrDefault("soh_cap", Double.NaN)); renderHv(list); });
            });
        });
        live.setOnClickListener(v -> {
            if (!needConn()) return;
            final int token = ++liveToken;
            runLive("Ao vivo — toque em Parar para terminar", () -> {
                while (!cancel && liveToken == token) {
                    readHv(false);
                    ui(() -> { soc.setValue(hv.getOrDefault("soc", Double.NaN)); soh.setValue(hv.getOrDefault("soh_cap", Double.NaN)); renderHv(list); });
                    Thread.sleep(1000);
                }
            });
        });
    }

    private void readHv(boolean all) throws Exception {
        for (IpaceParams.Param p : IpaceParams.all()) {
            if (cancel) break;
            if (!all && !(p.tx == IpaceModules.BECM && p.did < 0x4920)) continue;
            hv.put(p.key, p.decodeSafe(session.readDid(p.tx, p.did)));
        }
    }

    private void renderHv(LinearLayout list) {
        list.removeAllViews();
        if (hv.isEmpty()) return;
        String[][] groups = {
                {"Estado", "soc", "soc_min", "soc_max", "pack_v", "pack_i", "regen_max"},
                {"Saúde", "soh_cap", "soh_cap_min", "soh_cap_max", "soh_pow", "soh_pow_min", "soh_pow_max", "mod_flags"},
                {"Células", "cell_max", "cell_min"},
                {"Temperaturas", "t_max", "t_min", "t_avg", "cool_in", "cool_out", "plate_t1", "plate_t2", "plate_t3", "plate_t4", "plate_t5", "plate_t6"},
                {"Veículo", "odo", "cabin_t", "ambient_t", "speed", "tp_FE", "tp_FD", "tp_TE", "tp_TD", "tt_FE", "tt_FD", "tt_TE", "tt_TD"}};
        for (String[] g : groups) {
            if (g[0].equals("Temperaturas")) { renderTempCard(list, g); continue; }
            LinearLayout c = Theme.card(this);
            c.addView(Theme.title(this, g[0]));
            for (int i = 1; i < g.length; i++) {
                IpaceParams.Param p = IpaceParams.get(g[i]);
                if (p == null || !hv.containsKey(p.key)) continue;
                double v = hv.get(p.key);
                int col = Double.isNaN(v) ? Theme.MUTED : Theme.TEXT;
                if (p.key.equals("mod_flags") && !Double.isNaN(v)) col = v == 0 ? Theme.OK : Theme.WARN;
                c.addView(Theme.kv(this, p.name + (p.confirmed ? "" : " ·por confirmar"), p.format(v), col));
                if (g[0].equals("Células") && i == g.length - 1) {
                    double d = (hv.getOrDefault("cell_max", Double.NaN) - hv.getOrDefault("cell_min", Double.NaN)) * 1000;
                    c.addView(Theme.kv(this, "Diferença (máx. − mín.)", fmt(d, 0) + " mV",
                            Double.isNaN(d) ? Theme.MUTED : d < 30 ? Theme.OK : d < 60 ? Theme.WARN : Theme.BAD));
                }
            }
            if (g[0].equals("Saúde")) c.addView(Theme.muted(this, "\"Módulos sinalizados\" existe no software instalado pelo recall H441; 0 = nenhum módulo sinalizado. Se aparecer \"—\", o BECM não tem esse dado."));
            list.addView(c);
        }
        LinearLayout n = Theme.card(this);
        n.addView(Theme.muted(this, "\"por confirmar\" = endereço conhecido da comunidade mas fórmula ainda não validada. A saúde por módulo (36 valores) exige acesso de segurança ao BECM e não é lida por esta app."));
        list.addView(n);
    }

    // =====================================================================
    // 12V
    // =====================================================================
    private void screen12v() {
        LinearLayout top = card("Baterias de 12V", "O I-Pace tem duas: a principal (arranque) e a auxiliar. São carregadas pelo conversor DC/DC a partir da bateria de alta tensão.");
        Gauge g = new Gauge(this, "Tomada OBD (circuito principal)", "V", 10.5, 15, 2, MainActivity::v12Color);
        top.addView(g);
        TextView gState = Theme.muted(this, "");
        gState.setGravity(Gravity.CENTER);
        top.addView(gState);
        TextView tip = Theme.text(this, "Os ecrãs \"ao vivo\" falam com os módulos e mantêm o carro acordado. Para os testes 1 e 4 deixe a app neste separador.", 13, Theme.WARN, false);
        tip.setPadding(0, Theme.dp(this, 8), 0, 0);
        top.addView(tip);
        btn(top, "Medir agora", false, v -> {
            if (!needConn()) return;
            run("A medir…", false, () -> {
                double x = session.voltage();
                ui(() -> { g.setValue(x); gState.setText(x >= 13.3 ? "DC/DC a carregar" : x >= 12.0 ? "Sem carga do DC/DC" : "Tensão baixa"); });
            });
        });

        // Teste 1 — repouso
        LinearLayout t1 = card("1 · Carga em repouso", "Tranque o carro e espere 20–30 min (redes adormecidas; a JLR indica ~20 min). A medição usa só o adaptador e não acorda o carro.");
        TextView r1 = result(t1, "rest");
        btn(t1, "Medir repouso (10 s)", true, v -> {
            if (!needConn()) return;
            onDemoState(DemoCar.State.ASLEEP);
            run("A medir repouso…", true, () -> {
                List<TwelveVolt.Sample> s = new Recorder(session).voltageLog(1000, 10_000, null, null, progress);
                double avg = 0;
                for (TwelveVolt.Sample x : s) avg += x.v;
                avg = s.isEmpty() ? Double.NaN : avg / s.size();
                String verdict = TwelveVolt.restVerdict(avg);
                v12.put("rest", verdict);
                ui(() -> setResult(r1, verdict));
            });
        });

        // Teste 2 — acordar
        LinearLayout t2 = card("2 · Queda ao acordar", "Com o carro adormecido, toque em Iniciar e, nos 5 s seguintes, destranque e carregue em START (pé no travão). Mede a queda de tensão antes do DC/DC entrar.");
        TextView r2 = result(t2, "wake");
        btn(t2, "Iniciar captura (30 s)", true, v -> {
            if (!needConn()) return;
            onDemoState(DemoCar.State.ASLEEP);
            run("A capturar — destranque e ligue o carro agora", true, () -> {
                if (demo != null) new Thread(() -> { try { Thread.sleep(2500); } catch (InterruptedException ignored) { } onDemoState(DemoCar.State.READY); }).start();
                List<TwelveVolt.Sample> s = new Recorder(session).voltageLog(0, demo != null ? 8_000 : 30_000, null,
                        new Recorder.Listener() { public void live(String x) { step("A capturar: " + x); } public void event(String x) { } }, progress);
                String verdict = TwelveVolt.analyzeWake(s).verdict;
                v12.put("wake", verdict);
                ui(() -> setResult(r2, verdict));
            });
        });

        // Teste 3 — DC/DC
        LinearLayout t3 = card("3 · Carregamento pelo DC/DC", "Com o carro ligado (pronto a conduzir), mede durante 20 s. A JLR indica 13,2–15,2 V nas duas baterias com o carro ligado.");
        TextView r3 = result(t3, "dcdc");
        btn(t3, "Testar DC/DC (20 s)", true, v -> {
            if (!needConn()) return;
            if (demo != null && demo.state() != DemoCar.State.READY) { onDemoState(DemoCar.State.READY); }
            run("A testar o DC/DC…", true, () -> {
                if (demo != null) Thread.sleep(3200);
                List<TwelveVolt.Sample> s = new Recorder(session).voltageLog(500, 20_000, null, null, progress);
                String verdict = TwelveVolt.dcdcVerdict(s);
                v12.put("dcdc", verdict);
                ui(() -> setResult(r3, verdict));
            });
        });

        // Teste 4 — noite
        LinearLayout t4 = card("4 · Consumo durante a noite", "Deixe o telemóvel no carro, a carregar, com a app aberta. Lê a tensão a cada minuto até tocar em Parar (máx. 14 h). Deteta consumos parasitas e recargas automáticas do DC/DC.");
        TextView r4 = result(t4, "sleep");
        btn(t4, demo != null ? "Ver exemplo (noite simulada)" : "Iniciar registo noturno", true, v -> {
            if (!needConn()) return;
            if (demo != null) {
                List<TwelveVolt.Sample> s = new ArrayList<>();
                double x = 12.62; long t = 0;
                for (int i = 0; i < 600; i++) {
                    if (i == 260) x = 14.0; else if (i > 260 && i < 290) x = 12.7; else x -= 0.0011;
                    s.add(new TwelveVolt.Sample(t, x)); t += 60_000;
                }
                String verdict = "[SIMULADO] " + TwelveVolt.analyzeSleep(s).verdict;
                v12.put("sleep", verdict);
                setResult(r4, verdict);
                return;
            }
            run("Registo noturno em curso", true, () -> {
                Storage.LineFile f = new Storage.LineFile(this, "12v_noite_" + Storage.stamp() + ".csv");
                try {
                    List<TwelveVolt.Sample> s = new Recorder(session).voltageLog(60_000, 14 * 3_600_000L, f::line,
                            new Recorder.Listener() { public void live(String x) { step("Noite: " + x); } public void event(String x) { } }, progress);
                    String verdict = TwelveVolt.analyzeSleep(s).verdict + "\nFicheiro: " + f.path;
                    v12.put("sleep", verdict);
                    ui(() -> setResult(r4, verdict));
                } finally { f.close(); }
            });
        });

        // Substituição das baterias
        LinearLayout sw = card("Substituir baterias 12V", "Assistente em duas partes. Faça o ANTES com o carro como está, troque as baterias e depois faça o DEPOIS.");
        btn(sw, "1 · Antes da troca", true, v -> runProcedure("troca_12v_antes"));
        btn(sw, "2 · Depois da troca", true, v -> runProcedure("troca_12v_depois"));
        Procedure bms = defs.procedure("registo_bms");
        String bmsState = bms == null ? "não definido" : bms.available ? (bms.vins.isEmpty() ? "disponível" : "disponível para o VIN " + String.join(", ", bms.vins)) : "ainda não disponível";
        TextView bmsTxt = Theme.muted(this, "Registo da bateria principal (BMS): " + bmsState + (bms != null && !bms.source.isEmpty() ? " · " + bms.source : ""));
        bmsTxt.setPadding(0, Theme.dp(this, 10), 0, 0);
        sw.addView(bmsTxt);
        btn(sw, "Registo da bateria principal (BMS)", false, v -> runProcedure("registo_bms"));

        // Bateria auxiliar
        LinearLayout aux = card("Bateria auxiliar", "A tomada OBD mede o circuito principal. Para a auxiliar há duas vias: medir com multímetro, ou procurar se algum módulo reporta a tensão que recebe.");
        EditText in = Theme.input(this, "Tensão medida na auxiliar (ex.: 12.35)", true);
        aux.addView(in);
        TextView r5 = result(aux, "aux");
        btn(aux, "Avaliar leitura do multímetro", false, v -> {
            try {
                double x = Double.parseDouble(in.getText().toString().replace(',', '.'));
                String verdict = TwelveVolt.auxManualVerdict(x);
                v12.put("aux", verdict);
                setResult(r5, verdict);
            } catch (Exception e) { toast("Escreva um número, ex.: 12.35"); }
        });
        TextView r6 = Theme.body(this, candidatesText());
        r6.setPadding(0, Theme.dp(this, 10), 0, 0);
        aux.addView(r6);
        btn(aux, "Procurar a tensão 12V nos módulos (~3 min)", true, v -> {
            if (!needConn()) return;
            run("A procurar…", true, () -> {
                double ref = session.voltage();
                Discovery disc = new Discovery(session);
                List<Discovery.Candidate> all = new ArrayList<>();
                int[][] ranges = {{0xDD00, 0xDD1F}, {0xD100, 0xD11F}};
                for (int tx : IpaceModules.twelveVoltCandidates()) {
                    for (int[] rg : ranges) {
                        if (cancel) break;
                        all.addAll(Discovery.voltageCandidates(disc.sweep(tx, rg[0], rg[1], progress), ref, 0.45));
                    }
                }
                v12Candidates = all;
                for (Discovery.Candidate c : all) {
                    boolean dup = false;
                    for (Recorder.Extra e : extras) if (e.tx == c.f.tx && e.did == c.f.did) dup = true;
                    if (!dup) extras.add(new Recorder.Extra(c.f.tx, c.f.did, c.formula));
                }
                ui(() -> r6.setText(candidatesText()));
            });
        });
        btn(aux, "Comparar ao vivo com a tomada OBD", false, v -> {
            if (!needConn()) return;
            if (v12Candidates.isEmpty()) { toast("Primeiro procure a tensão nos módulos."); return; }
            final int token = ++liveToken;
            runLive("Comparação ao vivo", () -> {
                while (!cancel && liveToken == token) {
                    StringBuilder sb = new StringBuilder(String.format(Locale.US, "Tomada OBD: %.2f V\n", session.voltage()));
                    for (Discovery.Candidate c : v12Candidates) {
                        double x = Discovery.apply(c.formula, session.readDid(c.f.tx, c.f.did));
                        sb.append(String.format(Locale.US, "%s %s: %.2f V\n", IpaceModules.byTx(c.f.tx).code, Hex.h4(c.f.did), x));
                    }
                    String s = sb.toString();
                    ui(() -> r6.setText(s));
                    Thread.sleep(1000);
                }
            });
        });

        LinearLayout concl = card("Conclusão", null);
        concl.addView(Theme.body(this, conclusion12v()));
        concl.addView(spacer(8));
        concl.addView(Theme.muted(this, "A tensão mede sobretudo a carga. A capacidade real (saúde) só se mede com um testador de condutância, como o Midtronics que a JLR usa. A JLR recomenda resolver os problemas da 12V antes de diagnosticar a bateria de alta tensão."));
    }

    /** Cartão de temperaturas da bateria AT, gráfico e com cores por zona. */
    private void renderTempCard(LinearLayout list, String[] g) {
        LinearLayout c = Theme.card(this);
        LinearLayout head = Theme.row(this);
        TextView title = Theme.title(this, "Temperaturas da bateria");
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);
        double tmax = hv.getOrDefault("t_max", Double.NaN);
        if (!Double.isNaN(tmax)) head.addView(Theme.pill(this, Theme.tempZone(tmax).toUpperCase(Locale.ROOT), Theme.tempColor(tmax)));
        c.addView(head);
        c.addView(Theme.muted(this, "Verde = ideal (10–35 °C) · âmbar = intermédia (35–45) · vermelho = alta (>45) · azul = fria (<10)"));
        boolean any = false;
        for (int i = 1; i < g.length; i++) {
            IpaceParams.Param p = IpaceParams.get(g[i]);
            if (p == null || !hv.containsKey(p.key)) continue;
            double v = hv.get(p.key);
            if (Double.isNaN(v)) continue;
            any = true;
            c.addView(tempRow(p.name + (p.confirmed ? "" : " ·por confirmar"), v));
        }
        boolean anyExtra = false;
        for (IpaceParams.Param p : IpaceParams.byGroup("temp")) {
            if (!hv.containsKey(p.key)) continue;
            double v = hv.get(p.key);
            if (Double.isNaN(v)) continue;
            if (!anyExtra) { c.addView(spacer(6)); c.addView(Theme.muted(this, "Componentes (de pacotes importados):")); }
            anyExtra = true;
            any = true;
            c.addView(tempRow(p.name + (p.confirmed ? "" : " ·por confirmar"), v));
        }
        if (!any) c.addView(Theme.muted(this, "Sem leituras de temperatura. Toque em \"Ler valores\" com o carro acordado."));
        c.addView(spacer(6));
        if (!anyExtra) c.addView(Theme.muted(this, "Temperaturas de componentes (motor, inversores, DC/DC): ainda não disponíveis de origem. Importe um pacote (Mais → Atualizações) e aparecem aqui, com o mesmo aspeto gráfico."));
        list.addView(c);
    }

    /** Linha de temperatura: nome, valor colorido com a zona, e barra-termómetro. */
    private View tempRow(String label, double celsius) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Theme.dp(this, 9), 0, Theme.dp(this, 3));
        LinearLayout r = Theme.row(this);
        TextView k = Theme.text(this, label, 13, Theme.MUTED, false);
        k.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int col = Theme.tempColor(celsius);
        TextView v = Theme.text(this, fmt(celsius, 1) + " °C  ·  " + Theme.tempZone(celsius), 14, col, true);
        v.setGravity(Gravity.END);
        r.addView(k);
        r.addView(v);
        TempBar bar = new TempBar(this);
        bar.set(celsius, col);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = Theme.dp(this, 6);
        bar.setLayoutParams(bp);
        box.addView(r);
        box.addView(bar);
        return box;
    }

    private TextView result(LinearLayout parent, String key) {
        TextView t = Theme.body(this, v12.getOrDefault(key, ""));
        t.setPadding(0, Theme.dp(this, 10), 0, 0);
        t.setTextColor(Theme.verdictColor(v12.get(key)));
        parent.addView(t);
        return t;
    }

    private void setResult(TextView t, String s) {
        t.setText(s);
        t.setTextColor(Theme.verdictColor(s));
    }

    private String candidatesText() {
        if (v12Candidates.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Valores de tensão encontrados nos módulos (" + (demo != null ? "SIMULADOS" : "a confirmar") + "):\n");
        for (Discovery.Candidate c : v12Candidates) sb.append("• ").append(c.describe()).append('\n');
        sb.append("Se um módulo mostrar uma tensão claramente diferente da tomada OBD, pode estar alimentado por outro ramo (por ex. o da bateria auxiliar) — hipótese a confirmar com multímetro. Estes valores passam a ser gravados no Registo.");
        return sb.toString();
    }

    private String conclusion12v() {
        if (v12.isEmpty()) return "Faça os testes acima para obter uma conclusão.";
        StringBuilder sb = new StringBuilder();
        String[][] names = {{"rest", "Repouso"}, {"wake", "Acordar"}, {"dcdc", "DC/DC"}, {"sleep", "Noite"}, {"aux", "Auxiliar"}};
        int bad = 0, warn = 0;
        for (String[] n : names) {
            String s = v12.get(n[0]);
            if (s == null) continue;
            int col = Theme.verdictColor(s);
            if (col == Theme.BAD) bad++;
            if (col == Theme.WARN) warn++;
            sb.append(col == Theme.BAD ? "✖ " : col == Theme.WARN ? "▲ " : "✔ ").append(n[1]).append('\n');
        }
        sb.append('\n');
        if (bad > 0) sb.append("Há sinais claros de problema no sistema de 12V. Mande testar as duas baterias com testador de condutância e verificar o DC/DC e as ligações.");
        else if (warn > 0) sb.append("Há sinais de fragilidade. Repita os testes noutro dia e vigie.");
        else sb.append("Os testes feitos não mostram problemas no sistema de 12V.");
        return sb.toString();
    }

    // =====================================================================
    // REGISTO DE CARREGAMENTO
    // =====================================================================
    private void screenCharge() {
        LinearLayout info = card("Registo de carregamento", "Grava segundo a segundo a 12V e a bateria de alta tensão, e lê os erros do BECM, carregador e DC/DC a cada 10 s, marcando o instante em que aparece cada erro novo.");
        info.addView(Theme.body(this, "Como usar num posto rápido:\n1. Ligue o carro e a app; toque em Iniciar ANTES de ligar o cabo.\n2. Ligue o cabo e inicie o carregamento.\n3. Se falhar, deixe gravar mais 1 minuto e toque em Parar.\n4. O ficheiro fica em Transferências/IPaceDiag."));
        EditText ex = Theme.input(this, "DIDs extra (ex.: 7E4 4A0B, 726 DD02)", false);
        info.addView(ex);
        TextView extraTxt = Theme.muted(this, extrasText());
        info.addView(extraTxt);

        LinearLayout live = card("Ao vivo", null);
        TextView big = Theme.text(this, "— V", 44, Theme.MUTED, true);
        TextView sub = Theme.muted(this, "12V na tomada OBD");
        TextView summary = Theme.body(this, "");
        summary.setPadding(0, Theme.dp(this, 8), 0, 0);
        live.addView(big);
        live.addView(sub);
        live.addView(summary);

        LinearLayout ev = card("Eventos", lastChargeFile.isEmpty() ? null : "Último ficheiro: " + lastChargeFile);
        LinearLayout evList = new LinearLayout(this);
        evList.setOrientation(LinearLayout.VERTICAL);
        ev.addView(evList);
        for (String e : chargeEvents) evList.addView(eventView(e));

        btn(info, "Iniciar registo", true, v -> {
            if (!needConn()) return;
            for (String part : ex.getText().toString().split(",")) {
                String[] t = part.trim().split("\\s+");
                if (t.length == 2 && Hex.isHex(t[0]) && Hex.isHex(t[1])) {
                    int etx = Integer.parseInt(t[0], 16), edid = Integer.parseInt(t[1], 16);
                    boolean dup = false;
                    for (Recorder.Extra e : extras) if (e.tx == etx && e.did == edid) dup = true;
                    if (!dup) extras.add(new Recorder.Extra(etx, edid, null));
                }
            }
            extraTxt.setText(extrasText());
            chargeEvents.clear();
            evList.removeAllViews();
            if (demo != null) onDemoState(DemoCar.State.DC_CHARGING);
            run("A gravar o carregamento", true, () -> {
                Storage.LineFile f = new Storage.LineFile(this, "carregamento_" + Storage.stamp() + ".csv");
                lastChargeFile = f.path;
                try {
                    new Recorder(session).chargeLog(new ArrayList<>(extras), f::line, new Recorder.Listener() {
                        public void live(String s) {
                            String first = s.split("\n")[0];
                            double v12v = Double.NaN;
                            try { v12v = Double.parseDouble(first.replace("12V:", "").trim().split(" ")[0]); } catch (Exception ignored) { }
                            final double vv = v12v;
                            ui(() -> { big.setText(fmt(vv, 2) + " V"); big.setTextColor(v12Color(vv)); summary.setText(s); });
                        }
                        public void event(String e) {
                            chargeEvents.add(e);
                            ui(() -> evList.addView(eventView(e)));
                        }
                    }, progress);
                } finally {
                    f.close();
                    toast("Registo guardado: " + f.path);
                }
            });
        });
        if (demo != null) info.addView(Theme.muted(this, "Na demonstração, a falha (queda da 12V + erro de isolamento) acontece ao fim de ~20 s."));
    }

    private View eventView(String e) {
        TextView t = Theme.body(this, e);
        t.setTextColor(e.contains("NOVO ERRO") ? Theme.BAD : e.contains("BAIXA") ? Theme.WARN : Theme.MUTED);
        t.setPadding(0, Theme.dp(this, 6), 0, Theme.dp(this, 6));
        return t;
    }

    private String extrasText() {
        if (extras.isEmpty()) return "Sem DIDs extra.";
        StringBuilder sb = new StringBuilder("Também grava: ");
        for (Recorder.Extra e : extras) sb.append(IpaceModules.byTx(e.tx).code).append(' ').append(Hex.h4(e.did)).append("  ");
        return sb.toString();
    }

    // =====================================================================
    // NISSAN LEAF
    // =====================================================================
    private void screenLeafBattery() {
        LinearLayout top = card("Bateria do Leaf", "Nissan Leaf 2015 · 24 kWh. Leitura do controlador da bateria (LBC) por 0x79B/0x7BB. Valores por confirmar no carro.");
        LinearLayout gr = Theme.row(this);
        Gauge gSoh = new Gauge(this, "Saúde (SOH)", "%", 50, 100, 1, x -> x >= 85 ? Theme.OK : x >= 75 ? Theme.WARN : Theme.BAD);
        Gauge gSoc = new Gauge(this, "Carga (SOC)", "%", 0, 100, 1, x -> x < 20 ? Theme.WARN : Theme.TEAL);
        gSoh.setLayoutParams(Theme.weight(this, 0));
        gSoc.setLayoutParams(Theme.weight(this, 8));
        gr.addView(gSoh);
        gr.addView(gSoc);
        top.addView(gr);
        if (leafLast != null) { gSoh.setValue(leafLast.soh); gSoc.setValue(leafLast.soc); }

        if (session == null) {
            LinearLayout conn = card("Ligar", "Use o mesmo adaptador OBD do I-Pace (Bluetooth ou WiFi).");
            btn(conn, "Ligar (Bluetooth clássico)", true, v -> connectFlow());
            btn(conn, "Ligar (Bluetooth LE)", false, v -> connectFlowBle());
            btn(conn, "Ligar (OBD WiFi)", false, v -> connectFlowWifi());
            btn(conn, "Ligar (DoIP / Ethernet — avançado)", false, v -> connectFlowDoip());
            btn(conn, "Modo demonstração", false, v -> startDemo());
            return;
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        btn(top, "Ler bateria", true, v -> run("A ler a bateria do Leaf…", true, () -> {
            LeafBattery b = new LeafBattery().read(session);
            leafLast = b;
            ui(() -> { gSoh.setValue(b.soh); gSoc.setValue(b.soc); renderLeaf(list, b); });
        }));
        content.addView(list);
        if (leafLast != null) renderLeaf(list, leafLast);
        else list.addView(Theme.muted(this, "Toque em \"Ler bateria\" com o carro ligado (contacto)."));
    }

    private void renderLeaf(LinearLayout list, LeafBattery b) {
        list.removeAllViews();
        if (!b.ok) {
            LinearLayout e = Theme.card(this);
            e.addView(Theme.text(this, b.error.isEmpty() ? "Sem dados." : b.error, 14, Theme.WARN, false));
            list.addView(e);
            return;
        }
        LinearLayout c = Theme.card(this);
        c.addView(Theme.title(this, "Saúde"));
        c.addView(Theme.kv(this, "SOH (saúde)", LeafBattery.f(b.soh, 1, "%"), Double.isNaN(b.soh) ? Theme.MUTED : (b.soh >= 85 ? Theme.OK : b.soh >= 75 ? Theme.WARN : Theme.BAD)));
        c.addView(Theme.kv(this, "Hx (resistência interna)", LeafBattery.f(b.hx, 1, "%"), Theme.TEXT));
        c.addView(Theme.kv(this, "AHr (capacidade real)", LeafBattery.f(b.ahr, 2, "Ah"), Theme.TEXT));
        c.addView(Theme.kv(this, "Veredicto", b.sohVerdict(), Theme.verdictColor(b.sohVerdict())));
        list.addView(c);

        LinearLayout p = Theme.card(this);
        p.addView(Theme.title(this, "Pack"));
        p.addView(Theme.kv(this, "Tensão", LeafBattery.f(b.packV, 2, "V"), Theme.TEXT));
        p.addView(Theme.kv(this, "Corrente", LeafBattery.f(b.packA, 2, "A"), Theme.TEXT));
        p.addView(Theme.kv(this, "Carga (SOC)", LeafBattery.f(b.soc, 1, "%"), Theme.TEXT));
        list.addView(p);

        LinearLayout cl = Theme.card(this);
        cl.addView(Theme.title(this, "Células (" + b.cellCount + " pares)"));
        cl.addView(Theme.kv(this, "Mín / Méd / Máx", LeafBattery.f(b.cellMin(), 3, "") + " / " + LeafBattery.f(b.cellAvg(), 3, "") + " / " + LeafBattery.f(b.cellMax(), 3, "V"), Theme.TEXT));
        double d = b.cellDeltaMv();
        cl.addView(Theme.kv(this, "Diferença (delta)", LeafBattery.f(d, 0, "mV"), Double.isNaN(d) ? Theme.MUTED : d < 30 ? Theme.OK : d < 80 ? Theme.WARN : Theme.BAD));
        list.addView(cl);

        LinearLayout t = Theme.card(this);
        LinearLayout th = Theme.row(this);
        TextView tt = Theme.title(this, "Temperatura");
        tt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        th.addView(tt);
        if (!Double.isNaN(b.tMax)) th.addView(Theme.pill(this, Theme.tempZone(b.tMax).toUpperCase(Locale.ROOT), Theme.tempColor(b.tMax)));
        t.addView(th);
        t.addView(Theme.muted(this, "Verde = ideal (10–35 °C) · âmbar = intermédia · vermelho = alta · azul = fria"));
        if (!Double.isNaN(b.tMin)) t.addView(tempRow("Mínima", b.tMin));
        if (!Double.isNaN(b.tMax)) t.addView(tempRow("Máxima", b.tMax));
        list.addView(t);

        LinearLayout note = Theme.card(this);
        note.addView(Theme.muted(this, "A comunicação (0x79B/0x7BB, serviço 0x21) está sólida, mas as fórmulas exatas do Leaf não são totalmente públicas: confirme os números no seu carro ou por um pacote de definições. O LeafSpy continua a ser a referência completa."));
        list.addView(note);

        LinearLayout save = Theme.card(this);
        save.addView(Theme.title(this, "Guardar"));
        btn(save, "Guardar leitura (texto)", true, v -> run("A guardar…", false, () -> {
            StringBuilder sb = new StringBuilder("Leitura da bateria — Nissan Leaf 2015" + (demoMode ? " [DADOS SIMULADOS]" : "") + "\n");
            sb.append("SOH ").append(LeafBattery.f(b.soh, 1, "%")).append("   Hx ").append(LeafBattery.f(b.hx, 1, "%")).append("   AHr ").append(LeafBattery.f(b.ahr, 2, "Ah")).append('\n');
            sb.append("Pack ").append(LeafBattery.f(b.packV, 2, "V")).append("  ").append(LeafBattery.f(b.packA, 2, "A")).append("   SOC ").append(LeafBattery.f(b.soc, 1, "%")).append('\n');
            sb.append("Células ").append(b.cellCount).append(" pares  ").append(LeafBattery.f(b.cellMin(), 3, "")).append("–").append(LeafBattery.f(b.cellMax(), 3, "V")).append("  delta ").append(LeafBattery.f(b.cellDeltaMv(), 0, "mV")).append('\n');
            sb.append("Temperatura ").append(LeafBattery.f(b.tMin, 0, "")).append("–").append(LeafBattery.f(b.tMax, 0, "°C")).append("\n\n");
            for (int i = 0; i < b.cellCount; i++) sb.append("Célula ").append(i + 1).append(": ").append(LeafBattery.f(b.cells[i], 3, "V")).append('\n');
            sb.append('\n').append(Legal.SHORT);
            String path = Storage.saveText(this, "leaf_bateria_" + Storage.stamp() + ".txt", sb.toString());
            toast("Guardado: " + path);
        }));
        list.addView(save);
    }

    private void screenMoreLeaf() {
        LinearLayout carc = card("Carro", vehicle.name + " · " + vehicle.subtitle);
        btn(carc, "Trocar de carro", false, v -> screenCarPicker());

        LinearLayout adp = card("Adaptador e ligação", session == null ? "Desligado" : connectedName + (session.adapterId().isEmpty() ? "" : " · " + session.adapterId()));
        if (session != null) btn(adp, "Desligar", false, v -> disconnect());
        else {
            btn(adp, "Ligar (Bluetooth clássico)", true, v -> connectFlow());
            btn(adp, "Ligar (Bluetooth LE)", false, v -> connectFlowBle());
            btn(adp, "Ligar (OBD WiFi)", false, v -> connectFlowWifi());
            btn(adp, "Ligar (DoIP / Ethernet — avançado)", false, v -> connectFlowDoip());
            btn(adp, "Modo demonstração", false, v -> startDemo());
        }

        LinearLayout about = card("Sobre", "i-Pace · versão " + BuildConfig.VERSION_NAME);
        about.addView(Theme.body(this, "Perfil Nissan Leaf (2015, 24 kWh): lê o controlador da bateria (LBC) por CAN — endereço 0x79B, resposta em 0x7BB, serviço 0x21 por grupos — a mesma técnica do LeafSpy. Traz o núcleo da saúde da bateria; não é uma cópia completa do LeafSpy, e os valores devem ser confirmados no carro.\n\nNão altera o carro. Aviso: ao substituir a bateria do Leaf, nunca copie os dados do LBC antigo para um pack novo — o carro passaria a ler só 8 barras de capacidade."));
        btn(about, "Ler termos de utilização", false, v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Legal.TITLE).setMessage(Legal.TEXT).setPositiveButton("Fechar", null).show());
    }

    // =====================================================================
    // MAIS
    // =====================================================================
    private void screenMore() {
        if (vehicle != Vehicle.IPACE) { screenMoreLeaf(); return; }
        LinearLayout rep = card("Relatório completo", "Módulos, erros, bateria de alta tensão, testes de 12V e eventos do último registo. Guarda PDF e texto em Transferências/IPaceDiag.");
        btn(rep, "Gerar relatório completo", true, v -> saveReport(true));

        moreUpdatesAndCapture();

        LinearLayout disc = card("Descobrir parâmetros", "Percorre identificadores de um módulo com leitura (serviço 0x22) e grava os que respondem — útil para encontrar dados novos. Só leitura.");
        Spinner sp = new Spinner(this);
        List<String> labels = new ArrayList<>();
        for (IpaceModules.Module m : IpaceModules.all()) labels.add(m.code + " — " + m.name);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        sp.setAdapter(ad);
        sp.setBackground(Theme.round(this, Theme.SURFACE_2, Theme.STROKE, 12));
        sp.setLayoutParams(Theme.match(this, 10));
        disc.addView(sp);
        LinearLayout rr = Theme.row(this);
        EditText from = Theme.input(this, "De (hex) 4900", false);
        EditText to = Theme.input(this, "Até (hex) 49FF", false);
        from.setText("4900");
        to.setText("49FF");
        from.setLayoutParams(Theme.weight(this, 0));
        to.setLayoutParams(Theme.weight(this, 8));
        rr.addView(from);
        rr.addView(to);
        disc.addView(rr);
        TextView dres = Theme.mono(this, "");
        dres.setPadding(0, Theme.dp(this, 10), 0, 0);
        btn(disc, "Iniciar descoberta", true, v -> {
            if (!needConn()) return;
            int a, b;
            try { a = Integer.parseInt(from.getText().toString().trim(), 16); b = Integer.parseInt(to.getText().toString().trim(), 16); }
            catch (Exception e) { toast("Use valores hexadecimais, ex.: 4900 e 49FF"); return; }
            if (b < a || b - a > 0x0FFF) { toast("Gama inválida (máximo 4096 identificadores)."); return; }
            IpaceModules.Module m = IpaceModules.all().get(sp.getSelectedItemPosition());
            run("A descobrir…", true, () -> {
                List<Discovery.Found> f = new Discovery(session).sweep(m.tx, a, b, progress);
                StringBuilder csv = new StringBuilder("modulo,did,bytes,dados\n");
                StringBuilder view = new StringBuilder(f.size() + " identificadores responderam:\n");
                for (Discovery.Found x : f) {
                    csv.append(x.csv()).append('\n');
                    if (view.length() < 6000) view.append(Hex.h4(x.did)).append(": ").append(Hex.of(x.data)).append('\n');
                }
                String path = Storage.saveText(this, "descoberta_" + m.code + "_" + Storage.stamp() + ".csv", csv.toString());
                String out = view + "\nGuardado em " + path;
                ui(() -> dres.setText(out));
            });
        });
        disc.addView(dres);

        LinearLayout adp = card("Adaptador e ligação", session == null ? "Desligado" : connectedName + (session.adapterId().isEmpty() ? "" : " · " + session.adapterId()));
        CheckBox tl = new CheckBox(this);
        tl.setText("Gravar o tráfego bruto (ficheiro de texto, a partir da próxima ligação) — útil para análise técnica");
        tl.setTextColor(Theme.TEXT);
        tl.setChecked(trafficLog);
        tl.setOnCheckedChangeListener((x, on) -> trafficLog = on);
        adp.addView(tl);
        if (session != null) btn(adp, "Desligar", false, v -> disconnect());
        else {
            btn(adp, "Ligar (Bluetooth clássico)", true, v -> connectFlow());
            btn(adp, "Ligar (Bluetooth LE)", false, v -> connectFlowBle());
            btn(adp, "Ligar (OBD WiFi)", false, v -> connectFlowWifi());
            btn(adp, "Ligar (DoIP / Ethernet — avançado)", false, v -> connectFlowDoip());
            btn(adp, "Modo demonstração", false, v -> startDemo());
        }

        LinearLayout about = card("Sobre", "i-Pace · versão " + BuildConfig.VERSION_NAME + " (definições formato " + Definitions.APP_FORMAT + ")");
        about.addView(Theme.body(this,
                "Feita para o Jaguar I-Pace de 2018 (ano-modelo 2019), que usa a arquitetura elétrica anterior à EVA 2.0 (introduzida no I-Pace de 2021) e é anterior ao \"Secure Diagnostics\" da JLR (2025). "
                + "A leitura de dados por CAN está aberta; escrever configuração ou correr rotinas exige acesso de segurança da JLR e NÃO é feito por esta app. "
                + "Alguns módulos do I-Pace comunicam por Ethernet (DoIP), que um adaptador Bluetooth não alcança.\n\n"
                + "Endereços de módulos e fórmulas do BECM: projeto Open Vehicle Monitoring System (componente Jaguar I-Pace, licença MIT, © 2021 Didier Ernotte) e comunidade de proprietários. "
                + "Critérios de 12V: boletins JLR SSM74274 e SSM74458."));
        btn(about, "Ler termos de utilização e exclusão de responsabilidade", false, v -> {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Legal.TITLE).setMessage(Legal.TEXT).setPositiveButton("Fechar", null).show();
        });
        TextView acc = Theme.muted(this, "Termos aceites em " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date(prefs().getLong("terms_at", 0))));
        acc.setPadding(0, Theme.dp(this, 8), 0, 0);
        about.addView(acc);
    }


    // =====================================================================
    // DEFINIÇÕES (ATUALIZAÇÕES) E PROCEDIMENTOS
    // =====================================================================
    private File defsDir() {
        File d = new File(getFilesDir(), "defs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Carrega o pacote de fábrica, os pacotes importados e as capturas (e o pacote de demonstração, se pedido). */
    private void loadDefinitions(boolean withDemo) {
        Definitions d = new Definitions();
        try { d.apply(readAll(getAssets().open("definicoes_base.json")), "fábrica"); }
        catch (Exception e) { toast("Definições de fábrica com erro: " + e.getMessage()); }
        File[] files = defsDir().listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File f : files) {
                try { d.apply(readAll(new java.io.FileInputStream(f)), f.getName()); }
                catch (Exception e) { toast("Pacote " + f.getName() + " ignorado: " + e.getMessage()); }
            }
        }
        if (withDemo) {
            try { d.apply(readAll(getAssets().open("definicoes_demo.json")), "demonstração"); }
            catch (Exception e) { toast("Pacote de demonstração com erro: " + e.getMessage()); }
        }
        defs = d;
    }

    private void moreUpdatesAndCapture() {
        // ---- Atualizações ----
        LinearLayout up = card("Atualizações", "A app recebe novas funções através de pacotes de definições (.json): parâmetros, descrições de erros, módulos e procedimentos guiados — sem reinstalar. Mudanças no código da app chegam como nova versão do projeto (instalar por cima mantém os dados).");
        for (Definitions.Pack pk : defs.packs()) {
            TextView t = Theme.body(this, "• " + pk.summary() + "  [" + pk.source + "]");
            t.setPadding(0, Theme.dp(this, 6), 0, 0);
            up.addView(t);
        }
        btn(up, "Importar pacote de definições (.json)", true, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        });
        btn(up, "Exportar procedimentos capturados", false, v -> run("A exportar…", false, () -> {
            File f = new File(defsDir(), "zz_capturas.json");
            if (!f.exists()) { toast("Ainda não há procedimentos capturados."); return; }
            String path = Storage.saveText(this, "capturas_" + Storage.stamp() + ".json", readAll(new java.io.FileInputStream(f)));
            toast("Exportado: " + path);
        }));
        Button reset = Theme.danger(this, "Repor definições de fábrica");
        reset.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Repor definições de fábrica?")
                .setMessage("Apaga todos os pacotes importados e procedimentos capturados. Os ficheiros já exportados em Transferências não são apagados. Feche e volte a abrir a app no fim.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Repor", (d, w) -> {
                    File[] fs = defsDir().listFiles();
                    if (fs != null) for (File f : fs) f.delete();
                    loadDefinitions(demoMode && vehicle == Vehicle.IPACE);
                    showTab(moreTab());
                }).show());
        up.addView(reset);

        // ---- Procedimentos disponíveis ----
        LinearLayout pr = card("Procedimentos guiados", "Todos os procedimentos instalados (de fábrica, importados ou capturados).");
        for (Procedure p : defs.procedures()) {
            String why = p.blockedReason(lastCaptureVin);
            LinearLayout row = Theme.row(this);
            row.setPadding(0, Theme.dp(this, 8), 0, 0);
            TextView name = Theme.body(this, p.title);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(name);
            row.addView(Theme.pill(this, !p.available ? "INDISPONÍVEL" : p.needsSecurity ? "BLOQUEADO" : p.writesToCar() ? "ALTERA MÓDULOS" : "SÓ LEITURA",
                    !p.available || p.needsSecurity ? Theme.MUTED : p.writesToCar() ? Theme.WARN : Theme.OK));
            row.setOnClickListener(v -> runProcedure(p.id));
            pr.addView(row);
            if (!p.description.isEmpty()) pr.addView(Theme.muted(this, p.description));
        }
        pr.addView(spacer(4));
        pr.addView(Theme.muted(this, "Toque num procedimento para o executar."));

        // ---- Modo escuta ----
        LinearLayout sn = card("Modo escuta (capturar a oficina)", "Grava, sem interferir, o que a máquina de uma oficina envia ao carro — por exemplo, o registo de uma bateria nova. Precisa de um cabo OBD em Y: o OBDLink MX+ e a máquina da oficina ligados ao mesmo tempo.");
        sn.addView(Theme.body(this, "1. Ligue o cabo em Y e o adaptador, e ligue a app ao adaptador.\n2. Toque em Iniciar escuta ANTES de a oficina começar.\n3. Peça à oficina que faça o registo da bateria (BMS).\n4. Toque em Parar no fim. A app identifica a rotina usada."));
        TextView snRes = Theme.mono(this, lastCapture.isEmpty() ? "" : Sniffer.summary(lastCapture));
        snRes.setPadding(0, Theme.dp(this, 10), 0, 0);
        LinearLayout snBtns = new LinearLayout(this);
        snBtns.setOrientation(LinearLayout.VERTICAL);
        btn(sn, "Iniciar escuta", true, v -> {
            if (!needConn()) return;
            run("Em escuta — toque em Parar no fim", true, () -> {
                byte[] vin = session.readDid(IpaceModules.BECM, 0xF190);
                lastCaptureVin = vin == null ? "" : Hex.ascii(vin, 0);
                List<Sniffer.Line> lines = new ArrayList<>();
                session.monitor(l -> {
                    lines.add(new Sniffer.Line(System.currentTimeMillis(), l));
                    if (lines.size() % 20 == 0) step("Em escuta: " + lines.size() + " tramas");
                }, () -> cancel);
                String path = Storage.saveText(this, "escuta_" + Storage.stamp() + ".csv", Sniffer.csv(lines));
                lastCapture = Sniffer.events(Sniffer.messages(lines));
                String sum = Sniffer.summary(lastCapture) + "\nCaptura guardada: " + path;
                ui(() -> { snRes.setText(sum); renderCaptureChoices(snBtns); });
            });
        });
        sn.addView(snRes);
        sn.addView(snBtns);
        renderCaptureChoices(snBtns);
    }

    private void renderCaptureChoices(LinearLayout box) {
        box.removeAllViews();
        for (Map.Entry<String, Sniffer.Event> e : Sniffer.routineChoices(lastCapture).entrySet()) {
            Sniffer.Event ev = e.getValue();
            Button b = Theme.button(this, "Guardar \"" + e.getKey() + "\" como Registo BMS", false);
            b.setOnClickListener(v -> saveCaptured(ev, "registo_bms", "Registo da bateria principal (BMS) — capturado"));
            box.addView(b);
            Button b2 = Theme.button(this, "Guardar \"" + e.getKey() + "\" como outro procedimento", false);
            b2.setOnClickListener(v -> saveCaptured(ev, "captura_" + Storage.stamp(), "Rotina capturada " + e.getKey()));
            box.addView(b2);
        }
    }

    private void saveCaptured(Sniffer.Event ev, String id, String title) {
        Procedure p = Sniffer.toProcedure(lastCapture, ev, id, title, lastCaptureVin,
                "capturado em " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date()) + (demo != null ? " (SIMULADO)" : ""));
        String warn = p.needsSecurity
                ? "A máquina da oficina usou acesso de segurança antes desta rotina. A app pode guardar o registo, mas NÃO a consegue reproduzir."
                : "Esta rotina vai ficar associada a este carro (VIN " + (lastCaptureVin.isEmpty() ? "desconhecido" : lastCaptureVin) + ") e só pode ser executada nele.\n\n"
                + "Reproduzir uma rotina capturada é por sua conta e risco: a app não sabe ao certo o que a rotina faz, apenas que a máquina da oficina a usou com sucesso. Use-a só para a mesma finalidade (ex.: registo após troca da bateria principal).";
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Guardar procedimento capturado?")
                .setMessage(p.description + "\n\n" + warn)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar", (d, w) -> run("A guardar…", false, () -> {
                    File f = new File(defsDir(), "zz_capturas.json");
                    List<Procedure> list = new ArrayList<>();
                    if (f.exists()) {
                        Definitions tmp = new Definitions();
                        tmp.apply(readAll(new java.io.FileInputStream(f)), "capturas");
                        for (Procedure x : tmp.procedures()) if (!x.id.equals(p.id)) list.add(x);
                    }
                    list.add(p);
                    String json = Definitions.packJson("Procedimentos capturados", Storage.stamp(), list);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
                    defs.putProcedure(p);
                    toast("Guardado: " + p.title);
                    ui(() -> showTab(currentTab));
                })).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        run("A importar…", false, () -> {
            String json = readAll(getContentResolver().openInputStream(uri));
            Definitions test = new Definitions();
            Definitions.Pack pk = test.apply(json, "validação"); // valida primeiro, sem tocar no que está carregado
            String safe = pk.name.replaceAll("[^A-Za-z0-9_-]", "_");
            File f = new File(defsDir(), "p_" + Storage.stamp() + "_" + safe + ".json");
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
            loadDefinitions(demoMode && vehicle == Vehicle.IPACE);
            ui(() -> {
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Pacote instalado").setMessage(pk.summary()).setPositiveButton("OK", null).show();
                showTab(moreTab());
            });
        });
    }

    /** Executa um procedimento, mostrando o progresso num ecrã próprio. */
    private void runProcedure(String id) {
        if (!needConn()) return;
        Procedure p = defs.procedure(id);
        if (p == null) { toast("Procedimento \"" + id + "\" não está instalado."); return; }
        if (!p.available) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(p.title).setMessage(p.note.isEmpty() ? "Ainda não disponível." : p.note).setPositiveButton("OK", null).show();
            return;
        }
        Runnable go = () -> {
            content.removeAllViews();
            LinearLayout c = card(p.title, p.description.isEmpty() ? null : p.description);
            if (!p.source.isEmpty()) c.addView(Theme.muted(this, "Origem: " + p.source));
            LinearLayout logBox = new LinearLayout(this);
            logBox.setOrientation(LinearLayout.VERTICAL);
            c.addView(logBox);
            Button close = btn(c, "Fechar", false, v -> showTab(currentTab));
            run(p.title, true, () -> {
                ProcedureRunner r = new ProcedureRunner(session, new ProcedureRunner.Ui() {
                    public void log(String text, int level) {
                        ui(() -> {
                            TextView t = Theme.body(MainActivity.this, text);
                            t.setPadding(0, Theme.dp(MainActivity.this, 6), 0, 0);
                            t.setTextColor(level == ProcedureRunner.OK ? Theme.OK : level == ProcedureRunner.WARN ? Theme.WARN : level == ProcedureRunner.BAD ? Theme.BAD : Theme.TEXT);
                            logBox.addView(t);
                        });
                    }
                    public boolean confirm(String title, String text) { return askBlocking(title, text); }
                    public void saveReport(String label) throws Exception { saveProcedureReport(label); }
                    public void onScan(List<Diagnostics.ModuleInfo> mods) { lastScan = mods; }
                    public boolean cancelled() { return cancel; }
                }, defs::procedure);
                r.run(p);
            });
        };
        if (p.writesToCar()) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Procedimento que altera módulos")
                    .setMessage(p.title + "\n\nEste procedimento envia comandos que podem alterar o estado de módulos do carro (rotinas ou apagar erros). "
                            + "Cada um desses passos pede confirmação. Use-o com o carro parado, de preferência ligado a um carregador de 12V ou com as baterias em bom estado. "
                            + "A responsabilidade é sua (ver termos de utilização).")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Compreendo, continuar", (d, w) -> go.run())
                    .show();
        } else go.run();
    }

    /** Pergunta ao utilizador a partir do fio de trabalho e espera pela resposta. */
    private boolean askBlocking(String title, String text) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean answer = new AtomicBoolean(false);
        ui(() -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setMessage(text).setCancelable(false)
                .setNegativeButton("Não", (d, w) -> latch.countDown())
                .setPositiveButton("Sim", (d, w) -> { answer.set(true); latch.countDown(); })
                .show());
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return answer.get();
    }

    /** Constrói o relatório em PDF gráfico a partir dos dados já recolhidos. Devolve o caminho. */
    private String saveGraphicalPdf(String title, String filename) throws Exception {
        PdfReport pdf = new PdfReport(title, (demoMode ? "DADOS SIMULADOS · " : "") + vehicle.name + " · " + PdfReport.stampedDate());
        pdf.line("Adaptador: " + connectedName + (session != null && !session.adapterId().isEmpty() ? " · " + session.adapterId() : ""));
        if (session != null) {
            double volt = session.voltage();
            pdf.line(Double.isNaN(volt) ? "Tensão 12V na tomada OBD: — (ligação DoIP não mede)"
                    : String.format(Locale.US, "Tensão 12V na tomada OBD: %.1f V", volt));
        }
        if (lastScan != null) { pdf.summary(lastScan); pdf.modules(lastScan); }
        if (!hv.isEmpty()) {
            pdf.section("Bateria de alta tensão");
            for (IpaceParams.Param p : IpaceParams.all()) {
                if (!hv.containsKey(p.key)) continue;
                double v = hv.get(p.key);
                if ("°C".equals(p.unit) && !Double.isNaN(v)) pdf.tempBar(p.name + (p.confirmed ? "" : " ·por confirmar"), v);
                else pdf.kv(p.name + (p.confirmed ? "" : " ·por confirmar"), p.format(v), PdfReport.colInk());
            }
        }
        if (!v12.isEmpty() || !v12Candidates.isEmpty()) {
            pdf.section("Baterias de 12V", PdfReport.colInfo());
            String[][] names = {{"rest", "Repouso"}, {"wake", "Acordar"}, {"dcdc", "DC/DC"}, {"sleep", "Noite"}, {"aux", "Auxiliar"}};
            for (String[] n : names) { String val = v12.get(n[0]); if (val != null) pdf.line(n[1] + ": " + val); }
            for (Discovery.Candidate cc : v12Candidates) pdf.line("Módulo: " + cc.describe());
            pdf.line(conclusion12v());
        }
        if (!chargeEvents.isEmpty()) {
            pdf.section("Último registo de carregamento", PdfReport.colWarn());
            pdf.line("Ficheiro: " + lastChargeFile);
            for (String e : chargeEvents) pdf.line(e);
        }
        pdf.section("Possíveis origens e soluções", PdfReport.colInk());
        pdf.note("As origens e soluções indicadas por erro são orientações gerais para saber por onde começar e para documentar — não são um diagnóstico definitivo. Confirme sempre com medição ou com uma oficina.");
        pdf.section("Aviso legal", PdfReport.colBad());
        pdf.note(Legal.SHORT);
        return pdf.save(this, filename);
    }

    private void saveProcedureReport(String label) throws Exception {
        Report r = new Report("RELATÓRIO " + label.toUpperCase(Locale.ROOT) + " — i-Pace" + (demo != null ? " [DADOS SIMULADOS]" : ""));
        double volt = session.voltage();
        r.line(Double.isNaN(volt) ? "Tensão 12V na tomada OBD: —" : String.format(Locale.US, "Tensão 12V na tomada OBD: %.1f V", volt));
        if (lastScan != null) r.modules(lastScan);
        r.section("Aviso").line(Legal.SHORT);
        String st = Storage.stamp();
        Storage.saveText(this, label + "_" + st + ".txt", r.text());
        saveGraphicalPdf("Relatório " + label, label + "_" + st + ".pdf");
    }

    private void saveReport(boolean full) {
        if (!needConn()) return;
        run("A preparar o relatório…", true, () -> {
            if (full || lastScan == null) {
                Diagnostics d = new Diagnostics(session);
                if (lastScan == null) {
                    lastScan = d.scanModules(progress);
                    d.readAllDtcs(lastScan, progress);
                }
                if (full) { step("A ler a bateria…"); readHv(true); }
            }
            Report r = new Report("RELATÓRIO DE DIAGNÓSTICO — i-Pace" + (demo != null ? " [DADOS SIMULADOS — MODO DEMONSTRAÇÃO]" : ""));
            r.line("Adaptador: " + connectedName + " · " + session.adapterId());
            double volt = session.voltage();
            r.line(Double.isNaN(volt) ? "Tensão 12V na tomada OBD: — (ligação DoIP não mede)"
                    : String.format(Locale.US, "Tensão 12V na tomada OBD no momento: %.1f V", volt));
            r.modules(lastScan);
            if (!hv.isEmpty()) {
                r.section("Bateria de alta tensão");
                for (IpaceParams.Param p : IpaceParams.all()) {
                    if (!hv.containsKey(p.key)) continue;
                    r.line(p.name + (p.confirmed ? "" : " (por confirmar)") + ": " + p.format(hv.get(p.key)));
                }
            }
            if (!v12.isEmpty() || !v12Candidates.isEmpty()) {
                r.section("Baterias de 12V");
                for (Map.Entry<String, String> e : v12.entrySet()) r.line(e.getKey() + ": " + e.getValue());
                for (Discovery.Candidate c : v12Candidates) r.line("Módulo: " + c.describe());
                r.line(conclusion12v());
            }
            if (!chargeEvents.isEmpty()) {
                r.section("Eventos do último registo de carregamento");
                r.line("Ficheiro: " + lastChargeFile);
                for (String e : chargeEvents) r.line(e);
            }
            r.section("Aviso");
            r.line(Legal.SHORT);
            String st = Storage.stamp();
            Storage.saveText(this, "relatorio_" + st + ".txt", r.text());
            String p1 = saveGraphicalPdf("Relatório de diagnóstico", "relatorio_" + st + ".pdf");
            ui(() -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Relatório guardado")
                    .setMessage(p1 + "\n(e a versão .txt na mesma pasta)\n\nAbra com a app \"Os meus ficheiros\" → Transferências → IPaceDiag.")
                    .setPositiveButton("OK", null).show());
        });
    }
}
