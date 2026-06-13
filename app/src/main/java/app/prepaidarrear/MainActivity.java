package app.prepaidarrear;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.regex.*;
import javax.net.ssl.*;

/**
 * PrepaidArrear — Refactored Meter Info App
 * Lookup: Prepaid meter only
 * Output : Arrear Amount + Total Bill
 *
 * Developed by MD Jasim Uddin
 * Sub Assistant Engineer, BPDB
 * C&D / S&D Division Sunamganj, Distribution Zone PDB Sylhet
 */
public class MainActivity extends AppCompatActivity {

    // ── UI ──────────────────────────────────────────────────────────────────
    private EditText  meterInput;
    private Button    searchBtn;
    private LinearLayout resultContainer;

    // ── Constants ───────────────────────────────────────────────────────────
    // SERVER 1 — prepaid token-check (meter → consumer number)
    private static final String SERVER1_URL = "https://web.bpdbprepaid.gov.bd/bn/token-check";
    // SERVER 3 — prepaid customer info  (consumer number → arrear/bill)
    private static final String SERVER3_URL = "https://miscbillAPI.bpdb.gov.bd/API/v1/get-pre-customer_info/";
    // SERVER 2 — billing API            (consumer number → balance/arrear)
    private static final String SERVER2_URL = "https://billonwebAPI.bpdb.gov.bd/API/CustomerInformation/";

    // ── Lifecycle ────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        trustAllCertificates();

        meterInput       = findViewById(R.id.meterInput);
        searchBtn        = findViewById(R.id.searchBtn);
        resultContainer  = findViewById(R.id.resultContainer);

        searchBtn.setOnClickListener(v -> doSearch());

        // Allow pressing Enter/Search on keyboard
        meterInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                doSearch();
                return true;
            }
            return false;
        });
    }

    // ── Search entry-point ───────────────────────────────────────────────────
    private void doSearch() {
        String meterNo = meterInput.getText().toString().trim();

        // Dismiss keyboard
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(meterInput.getWindowToken(), 0);

        if (meterNo.isEmpty()) {
            showStatus("Please enter a meter number.", false);
            return;
        }
        if (meterNo.length() != 12) {
            showStatus("Prepaid meter number must be exactly 12 digits.", false);
            return;
        }

        showLoading(meterNo);

        new Thread(() -> {
            try {
                // Step 1: meter → consumer number
                String consumerNo = fetchConsumerNumber(meterNo);
                if (consumerNo == null || consumerNo.isEmpty()) {
                    runOnUiThread(() -> showError("Meter not found or SERVER 1 unavailable.\nMeter: " + meterNo));
                    return;
                }

                // Reject obviously bad consumer numbers (contain letters → error token)
                if (consumerNo.matches(".*[A-Za-z].*")) {
                    runOnUiThread(() -> showError("Invalid response for meter: " + meterNo
                            + "\n(Got: " + consumerNo + ")"));
                    return;
                }

                // Step 2: consumer number → arrear + total bill
                ArrearResult ar = fetchArrear(consumerNo, meterNo);

                runOnUiThread(() -> showResult(meterNo, consumerNo, ar));

            } catch (Exception e) {
                runOnUiThread(() -> showError("Unexpected error: " + e.getMessage()));
            }
        }).start();
    }

    // ── SERVER 1: meter → consumer number ───────────────────────────────────
    private String fetchConsumerNumber(String meterNo) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(SERVER1_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept",       "text/x-component");
            conn.setRequestProperty("Content-Type", "text/plain;charset=UTF-8");
            conn.setRequestProperty("Next-Action",  "29e85b2c55c9142822fe8da82a577612d9e58bb2");
            conn.setRequestProperty("Origin",       "http://web.bpdbprepaid.gov.bd");
            conn.setRequestProperty("Referer",      "http://web.bpdbprepaid.gov.bd/bn/token-check");
            conn.setRequestProperty("User-Agent",   "Mozilla/5.0");

            String body = "[{\"meterNo\":\"" + meterNo + "\"}]";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }

            if (conn.getResponseCode() == 200) {
                String response = readStream(conn.getInputStream());
                // Extract customerNo from response
                Pattern p = Pattern.compile("\"customerNo\"\\s*:\\s*\"([A-Za-z0-9]+)\"");
                Matcher m = p.matcher(response);
                if (m.find()) return m.group(1);
            }
        } catch (Exception e) {
            android.util.Log.e("SERVER1", "Error: " + e.getMessage());
        }
        return null;
    }

    // ── ArrearResult holder ──────────────────────────────────────────────────
    static class ArrearResult {
        String customerName = "—";
        String address      = "—";
        String tariff       = "—";
        double arrearAmount = Double.NaN;  // NaN = not found
        double totalBill    = Double.NaN;
        String source       = "—";        // SERVER3 / SERVER2 / none
    }

    // ── Fetch arrear from SERVER 3 then SERVER 2 as fallback ─────────────────
    private ArrearResult fetchArrear(String consumerNo, String meterNo) {
        ArrearResult ar = new ArrearResult();

        // ── Try SERVER 3 first ───────────────────────────────────────────────
        try {
            HttpsURLConnection conn = (HttpsURLConnection) new URL(SERVER3_URL + consumerNo).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Accept",     "application/json");

            if (conn.getResponseCode() == 200) {
                String response = readStream(conn.getInputStream());
                JSONObject obj  = new JSONObject(response);

                String cName = obj.optString("customerName", "").trim();
                if (!cName.isEmpty()) {
                    // SERVER 3 has valid customer data
                    ar.customerName = cName;
                    ar.address      = obj.optString("customerAddress",  "—");
                    ar.tariff       = obj.optString("tariffCategory",   "—");

                    // arrearAmount field
                    if (obj.has("arrearAmount")) {
                        try { ar.arrearAmount = Double.parseDouble(obj.optString("arrearAmount", "NaN")); }
                        catch (NumberFormatException ignored) {}
                    }

                    // totalBill: check totalBillAmount, totalAmount, currentBill
                    for (String key : new String[]{"totalBillAmount","totalAmount","currentBill","billAmount"}) {
                        if (obj.has(key)) {
                            try {
                                ar.totalBill = obj.getDouble(key);
                                break;
                            } catch (Exception ignored) {}
                        }
                    }

                    ar.source = "SERVER3";
                }
            }
        } catch (Exception e) {
            android.util.Log.e("SERVER3", "Error: " + e.getMessage());
        }

        // ── Try SERVER 2 (always, as it may have richer balance data) ────────
        try {
            HttpsURLConnection conn = (HttpsURLConnection) new URL(SERVER2_URL + consumerNo).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Accept",     "application/json");

            if (conn.getResponseCode() == 200) {
                String    response = readStream(conn.getInputStream());
                JSONObject obj     = new JSONObject(response);

                // ── Customer name from customerInfo array ────────────────────
                if (ar.customerName.equals("—") && obj.has("customerInfo")) {
                    try {
                        JSONArray outer = obj.getJSONArray("customerInfo");
                        if (outer.length() > 0) {
                            JSONArray inner = outer.getJSONArray(0);
                            if (inner.length() > 0) {
                                JSONObject ci = inner.getJSONObject(0);
                                String n = ci.optString("CUSTOMER_NAME", "").trim();
                                if (!n.isEmpty()) ar.customerName = n;
                                ar.address = ci.optString("ADDRESS", ar.address);
                                ar.tariff  = ci.optString("TARIFF",  ar.tariff);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // ── Arrear + Total from finalBalanceInfo (string) ────────────
                if ((Double.isNaN(ar.arrearAmount) || Double.isNaN(ar.totalBill))
                        && obj.has("finalBalanceInfo")) {
                    String fbi = obj.optString("finalBalanceInfo", "");
                    if (!fbi.equals("null") && !fbi.isEmpty()) {
                        // Extract ARREAR_AMT
                        if (Double.isNaN(ar.arrearAmount)) {
                            double v = extractNumericField(fbi, "ARREAR_AMT");
                            if (!Double.isNaN(v)) ar.arrearAmount = v;
                        }
                        // Extract TOTAL_AMT or BALANCE
                        if (Double.isNaN(ar.totalBill)) {
                            double v = extractNumericField(fbi, "TOTAL_AMT");
                            if (Double.isNaN(v)) v = extractNumericField(fbi, "BALANCE");
                            if (!Double.isNaN(v)) ar.totalBill = v;
                        }
                    }
                }

                // ── Arrear + Total from balanceInfo object ───────────────────
                if ((Double.isNaN(ar.arrearAmount) || Double.isNaN(ar.totalBill))
                        && obj.has("balanceInfo")) {
                    try {
                        JSONObject bi = obj.getJSONObject("balanceInfo");
                        if (bi.has("Result") && bi.getJSONArray("Result").length() > 0) {
                            JSONObject r = bi.getJSONArray("Result").getJSONObject(0);
                            if (Double.isNaN(ar.arrearAmount))
                                ar.arrearAmount = r.optDouble("ARREAR_BILL", Double.NaN);
                            if (Double.isNaN(ar.totalBill)) {
                                double bal = r.optDouble("BALANCE", Double.NaN);
                                if (!Double.isNaN(bal)) ar.totalBill = bal;
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // ── Total from latest billInfo record ────────────────────────
                if (Double.isNaN(ar.totalBill) && obj.has("billInfo")) {
                    try {
                        JSONArray bills = obj.getJSONArray("billInfo");
                        if (bills.length() > 0) {
                            ar.totalBill = bills.getJSONObject(0).optDouble("TOTAL_BILL", Double.NaN);
                        }
                    } catch (Exception ignored) {}
                }

                if (ar.source.equals("—")) ar.source = "SERVER2";
                else ar.source += "+SERVER2";
            }
        } catch (Exception e) {
            android.util.Log.e("SERVER2", "Error: " + e.getMessage());
        }

        return ar;
    }

    // ── Extract a named numeric field from an inlined balance string ─────────
    private double extractNumericField(String text, String fieldName) {
        try {
            Pattern p = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*([\\-0-9.]+)");
            Matcher m = p.matcher(text);
            if (m.find()) return Double.parseDouble(m.group(1));
        } catch (Exception ignored) {}
        return Double.NaN;
    }

    // ── Display helpers ──────────────────────────────────────────────────────

    private void showLoading(String meterNo) {
        resultContainer.removeAllViews();

        TextView tv = new TextView(this);
        tv.setText("⏳  Searching for meter " + meterNo + "…");
        tv.setTextColor(0xFF37474F);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(0, 8, 0, 8);
        resultContainer.addView(tv);
    }

    private void showStatus(String msg, boolean isError) {
        resultContainer.removeAllViews();
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextColor(isError ? 0xFFC62828 : 0xFF37474F);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(0, 8, 0, 8);
        resultContainer.addView(tv);
    }

    private void showError(String msg) {
        resultContainer.removeAllViews();
        addCard(makeErrorCard(msg));
    }

    private void showResult(String meterNo, String consumerNo, ArrearResult ar) {
        resultContainer.removeAllViews();

        if (Double.isNaN(ar.arrearAmount)) {
            addCard(makeErrorCard("তথ্য পাওয়া যায়নি।\nমিটার: " + meterNo));
            return;
        }

        addCard(makeResultCard(consumerNo, ar));
    }

    // ── Card builders ────────────────────────────────────────────────────────

    private LinearLayout makeResultCard(String consumerNo, ArrearResult ar) {
        LinearLayout card = baseCard();

        // Customer Name
        if (!ar.customerName.equals("—")) {
            addRow(card, "গ্রাহকের নাম", ar.customerName, true);
            card.addView(makeDivider());
        }

        // Address
        if (!ar.address.equals("—")) {
            addRow(card, "ঠিকানা", ar.address, false);
            card.addView(makeDivider());
        }

        // Consumer No
        addRow(card, "কনজ্যুমার নং", consumerNo, false);
        card.addView(makeDivider());

        // বকেয়া — big centered line
        String amount = String.format("%,.0f", ar.arrearAmount);
        TextView tv = new TextView(this);
        tv.setText("বকেয়া  " + amount + "  টাকা");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(ar.arrearAmount > 0 ? 0xFFC62828 : 0xFF2E7D32);
        tv.setPadding(0, dp(14), 0, dp(6));
        card.addView(tv);

        return card;
    }

    private LinearLayout makeErrorCard(String msg) {
        LinearLayout card = baseCard();
        card.setBackgroundColor(0xFFFFF3F3);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFF3F3);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFFEF9A9A);
        card.setBackground(bg);

        TextView tv = new TextView(this);
        tv.setText("❌  " + msg);
        tv.setTextColor(0xFFC62828);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        card.addView(tv);
        return card;
    }

    // ── Card primitives ──────────────────────────────────────────────────────

    private LinearLayout baseCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFFE0E0E0);
        card.setBackground(bg);
        card.setElevation(dp(2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        return card;
    }

    private void addCard(LinearLayout card) {
        resultContainer.addView(card);
    }

    private void addRow(LinearLayout parent, String label, String value, boolean bold) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(0xFF607D8B);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lbl.setLayoutParams(llp);

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextColor(0xFF212121);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        if (bold) val.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f);
        val.setLayoutParams(vlp);

        row.addView(lbl);
        row.addView(val);
        parent.addView(row);
    }

    private void addBigRow(LinearLayout parent, String label, String value, int valueColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(0xFF37474F);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        lbl.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lbl.setLayoutParams(llp);

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextColor(valueColor);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        val.setTypeface(null, Typeface.BOLD);
        val.setGravity(Gravity.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f);
        val.setLayoutParams(vlp);

        row.addView(lbl);
        row.addView(val);
        parent.addView(row);
    }

    private View makeDivider() {
        android.view.View v = new android.view.View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(0, dp(4), 0, dp(4));
        v.setLayoutParams(lp);
        v.setBackgroundColor(0xFFEEEEEE);
        return v;
    }

    // ── SSL helpers ──────────────────────────────────────────────────────────

    private static void trustAllCertificates() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAll, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private String readStream(InputStream is) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    /** Convert dp to px */
    private int dp(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }
}
