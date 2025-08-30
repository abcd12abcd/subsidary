// GreenH2SingleFile.java
// Single-file Java backend demo: HTTP server + JDBC + simple JSON (no external JSON libs).
// Edit DB_URL/DB_USER/DB_PASS before compile/run.

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.time.Instant;

public class GreenH2SingleFile {

    // ---------- CONFIG: edit these ----------
//    private static final String DB_URL  = "jdbc:postgresql://localhost:8080/greenh2"; // or jdbc:mysql://host:port/db
//    private static final String DB_USER = "your_db_user";
//    private static final String DB_PASS = "your_db_pass";
 //   private static final String DB_URL  = "jdbc:mysql://localhost:3306/greenh2?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String DB_URL  = "jdbc:mysql://localhost:3306/greenh2";



    private static final String DB_USER = "root";
    private static final String DB_PASS = ""; // or your root password

    private static final int    PORT    = 8080;
    // ----------------------------------------

    public static void main(String[] args) throws Exception {
        // start HTTP server




        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/projects", new ProjectsHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("Server running at http://localhost:" + PORT + "/api/projects");
    }

    // ---------- HTTP Handler ----------
    static class ProjectsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            String[] parts = path.split("/");

            try {
                if ("GET".equalsIgnoreCase(method) && parts.length == 3) {
                    // GET /api/projects
                    List<Project> list = DAO.listProjects();
                    send(ex, 200, listToJson(list));
                    return;
                }
                if ("POST".equalsIgnoreCase(method) && parts.length == 3) {
                    // POST /api/projects  body: name=...&producer=...&requiredVolume=...&milestones=desc:target;...
                    String body = readBody(ex);
                    Map<String,String> form = parseForm(body);
                    String name = form.getOrDefault("name","Unnamed");
                    String producer = form.getOrDefault("producer","Producer");
                    double requiredVolume = parseDouble(form.get("requiredVolume"), 0.0);
                    String msRaw = form.getOrDefault("milestones","");
                    List<Milestone> ms = parseMilestonesFromString(msRaw);
                    Project p = DAO.createProject(name, producer, requiredVolume, ms);
                    send(ex,200,projectToJson(p));
                    return;
                }
                if ("GET".equalsIgnoreCase(method) && parts.length == 4) {
                    // GET /api/projects/{id}
                    long id = Long.parseLong(parts[3]);
                    Project p = DAO.getProject(id);
                    if (p == null) { send(ex,404,"{}"); return; }
                    send(ex,200,projectToJson(p));
                    return;
                }
                if ("POST".equalsIgnoreCase(method) && parts.length == 5 && "report".equals(parts[4])) {
                    // POST /api/projects/{id}/report   body: produced=...&reporter=...
                    long id = Long.parseLong(parts[3]);
                    String body = readBody(ex);
                    Map<String,String> form = parseForm(body);
                    double produced = parseDouble(form.get("produced"), 0.0);
                    String reporter = form.getOrDefault("reporter", "oracle");
                    Project p = DAO.reportProduction(id, produced, reporter);
                    send(ex,200,projectToJson(p));
                    return;
                }
                send(ex,404,"{\"error\":\"Unknown path\"}");
            } catch (Exception e) {
                e.printStackTrace();
                send(ex,500,"{\"error\":\"" + escape(e.getMessage()) + "\"}");
            }
        }
    }

    // ---------- DAO (JDBC) ----------
    static class DAO {

        // create project, insert milestones and an initial audit entry
        public static Project createProject(String name, String producer, double requiredVolume, List<Milestone> milestones) throws SQLException {
            try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                c.setAutoCommit(false);
                try {
                    String psql = "INSERT INTO projects (name, producer, required_volume) VALUES (?, ?, ?)";
                    try (PreparedStatement ps = c.prepareStatement(psql, Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, name);
                        ps.setString(2, producer);
                        ps.setDouble(3, requiredVolume);
                        ps.executeUpdate();
                        long projectId = -1;
                        try (ResultSet rk = ps.getGeneratedKeys()) {
                            if (rk.next()) projectId = rk.getLong(1);
                        }
                        if (projectId == -1) throw new SQLException("Failed to create project");

                        if (milestones != null && !milestones.isEmpty()) {
                            String msql = "INSERT INTO milestones (project_id, description, target_volume, achieved, disbursed) VALUES (?, ?, ?, false, false)";
                            try (PreparedStatement mps = c.prepareStatement(msql)) {
                                for (Milestone m : milestones) {
                                    mps.setLong(1, projectId);
                                    mps.setString(2, m.description);
                                    mps.setDouble(3, m.targetVolume);
                                    mps.addBatch();
                                }
                                mps.executeBatch();
                            }
                        }

                        String asql = "INSERT INTO audits (project_id, actor, message) VALUES (?, ?, ?)";
                        try (PreparedStatement aps = c.prepareStatement(asql)) {
                            aps.setLong(1, projectId);
                            aps.setString(2, "system");
                            aps.setString(3, "Project created");
                            aps.executeUpdate();
                        }

                        c.commit();
                        return getProject(projectId);
                    }
                } catch (SQLException ex) {
                    c.rollback();
                    throw ex;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        }

        // list projects (basic info)
        public static List<Project> listProjects() throws SQLException {
            List<Project> out = new ArrayList<>();
            try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                String sql = "SELECT id, name, producer, required_volume, total_produced, funds_disbursed FROM projects ORDER BY id";
                try (PreparedStatement ps = c.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Project p = new Project();
                        p.id = rs.getLong("id");
                        p.name = rs.getString("name");
                        p.producer = rs.getString("producer");
                        p.requiredVolume = rs.getDouble("required_volume");
                        p.totalProduced = rs.getDouble("total_produced");
                        p.fundsDisbursed = rs.getDouble("funds_disbursed");
                        out.add(p);
                    }
                }
            }
            return out;
        }

        // get project with milestones & audit
        public static Project getProject(long id) throws SQLException {
            try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                String sql = "SELECT id, name, producer, required_volume, total_produced, funds_disbursed FROM projects WHERE id = ?";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return null;
                        Project p = new Project();
                        p.id = rs.getLong("id");
                        p.name = rs.getString("name");
                        p.producer = rs.getString("producer");
                        p.requiredVolume = rs.getDouble("required_volume");
                        p.totalProduced = rs.getDouble("total_produced");
                        p.fundsDisbursed = rs.getDouble("funds_disbursed");

                        // milestones
                        String msql = "SELECT id, description, target_volume, achieved, disbursed, achieved_at FROM milestones WHERE project_id = ? ORDER BY id";
                        try (PreparedStatement mps = c.prepareStatement(msql)) {
                            mps.setLong(1, id);
                            try (ResultSet mrs = mps.executeQuery()) {
                                while (mrs.next()) {
                                    Milestone m = new Milestone();
                                    m.id = mrs.getLong("id");
                                    m.projectId = id;
                                    m.description = mrs.getString("description");
                                    m.targetVolume = mrs.getDouble("target_volume");
                                    m.achieved = mrs.getBoolean("achieved");
                                    m.disbursed = mrs.getBoolean("disbursed");
                                    p.milestones.add(m);
                                }
                            }
                        }

                        // audits
                        String asql = "SELECT id, actor, message, created_at FROM audits WHERE project_id = ? ORDER BY id";
                        try (PreparedStatement aps = c.prepareStatement(asql)) {
                            aps.setLong(1, id);
                            try (ResultSet ars = aps.executeQuery()) {
                                while (ars.next()) {
                                    AuditEvent a = new AuditEvent();
                                    a.id = ars.getLong("id");
                                    a.projectId = id;
                                    a.actor = ars.getString("actor");
                                    a.message = ars.getString("message");
                                    a.createdAt = ars.getTimestamp("created_at") != null ? ars.getTimestamp("created_at").toInstant() : Instant.now();
                                    p.audit.add(a);
                                }
                            }
                        }
                        return p;
                    }
                }
            }
        }

        // report production: transactional update + milestone check+disburse + audits
        public static Project reportProduction(long projectId, double produced, String reporter) throws SQLException {
            try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                c.setAutoCommit(false);
                try {
                    // update total_produced
                    String up = "UPDATE projects SET total_produced = total_produced + ? WHERE id = ?";
                    try (PreparedStatement ups = c.prepareStatement(up)) {
                        ups.setDouble(1, produced);
                        ups.setLong(2, projectId);
                        ups.executeUpdate();
                    }

                    // add audit
                    String ains = "INSERT INTO audits (project_id, actor, message) VALUES (?, ?, ?)";
                    try (PreparedStatement aps = c.prepareStatement(ains)) {
                        aps.setLong(1, projectId);
                        aps.setString(2, reporter);
                        aps.setString(3, "Reported production: " + produced);
                        aps.executeUpdate();
                    }

                    // check total_produced
                    double totalProduced = 0;
                    try (PreparedStatement tps = c.prepareStatement("SELECT total_produced FROM projects WHERE id = ?")) {
                        tps.setLong(1, projectId);
                        try (ResultSet trs = tps.executeQuery()) {
                            if (trs.next()) totalProduced = trs.getDouble("total_produced");
                        }
                    }

                    // find milestones not achieved yet and mark+disburse if reached
                    String msql = "SELECT id, target_volume, achieved, description FROM milestones WHERE project_id = ? ORDER BY id";
                    try (PreparedStatement mps = c.prepareStatement(msql)) {
                        mps.setLong(1, projectId);
                        try (ResultSet mrs = mps.executeQuery()) {
                            while (mrs.next()) {
                                long mid = mrs.getLong("id");
                                double target = mrs.getDouble("target_volume");
                                boolean achieved = mrs.getBoolean("achieved");
                                String desc = mrs.getString("description");
                                if (!achieved && totalProduced >= target) {
                                    double amount = target * 1000.0; // simple formula

                                    // update milestone
                                    try (PreparedStatement updM = c.prepareStatement("UPDATE milestones SET achieved = true, disbursed = true, achieved_at = now() WHERE id = ?")) {
                                        updM.setLong(1, mid);
                                        updM.executeUpdate();
                                    }

                                    // update project funds_disbursed
                                    try (PreparedStatement updP = c.prepareStatement("UPDATE projects SET funds_disbursed = funds_disbursed + ? WHERE id = ?")) {
                                        updP.setDouble(1, amount);
                                        updP.setLong(2, projectId);
                                        updP.executeUpdate();
                                    }

                                    // audits
                                    try (PreparedStatement a1 = c.prepareStatement("INSERT INTO audits (project_id, actor, message) VALUES (?, ?, ?)")) {
                                        a1.setLong(1, projectId);
                                        a1.setString(2, "verifier");
                                        a1.setString(3, "Milestone achieved: " + desc);
                                        a1.executeUpdate();
                                    }
                                    try (PreparedStatement a2 = c.prepareStatement("INSERT INTO audits (project_id, actor, message) VALUES (?, ?, ?)")) {
                                        a2.setLong(1, projectId);
                                        a2.setString(2, "bank");
                                        a2.setString(3, "Disbursed " + amount + " for milestone " + desc);
                                        a2.executeUpdate();
                                    }
                                }
                            }
                        }
                    }

                    c.commit();
                    c.setAutoCommit(true);
                    return getProject(projectId);
                } catch (SQLException ex) {
                    c.rollback();
                    throw ex;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        }
    }

    // ---------- Simple models ----------
    static class Project {
        long id;
        String name;
        String producer;
        double requiredVolume;
        double totalProduced;
        double fundsDisbursed;
        List<Milestone> milestones = new ArrayList<>();
        List<AuditEvent> audit = new ArrayList<>();
    }

    static class Milestone {
        long id;
        long projectId;
        String description;
        double targetVolume;
        boolean achieved;
        boolean disbursed;
    }

    static class AuditEvent {
        long id;
        long projectId;
        String actor;
        String message;
        Instant createdAt;
    }

    // ---------- Helpers: I/O, parsing, JSON builders ----------

    static String readBody(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int r;
        while ((r = in.read(buf)) > 0) baos.write(buf, 0, r);
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    static Map<String,String> parseForm(String body) {
        Map<String,String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;
        // supports form: a=1&b=2
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=",2);
            String k = urlDecode(kv[0]);
            String v = kv.length>1 ? urlDecode(kv[1]) : "";
            map.put(k, v);
        }
        return map;
    }

    static String urlDecode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    static double parseDouble(String s, double def) {
        if (s == null) return def;
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }

    static List<Milestone> parseMilestonesFromString(String raw) {
        // format: desc:target;desc2:target2
        List<Milestone> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String part : raw.split(";")) {
            if (!part.contains(":")) continue;
            String[] kv = part.split(":",2);
            String d = kv[0].trim();
            double t = parseDouble(kv[1].trim(), 0.0);
            Milestone m = new Milestone();
            m.description = d;
            m.targetVolume = t;
            out.add(m);
        }
        return out;
    }

    // ----- JSON builders (manual) -----
    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
    }

    static String projectToJson(Project p) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"id\":").append(p.id).append(",");
        sb.append("\"name\":\"").append(escape(p.name)).append("\",");
        sb.append("\"producer\":\"").append(escape(p.producer)).append("\",");
        sb.append("\"requiredVolume\":").append(p.requiredVolume).append(",");
        sb.append("\"totalProduced\":").append(p.totalProduced).append(",");
        sb.append("\"fundsDisbursed\":").append(p.fundsDisbursed).append(",");
        // milestones
        sb.append("\"milestones\":[");
        for (int i=0;i<p.milestones.size();i++){
            Milestone m = p.milestones.get(i);
            if (i>0) sb.append(",");
            sb.append("{");
            sb.append("\"id\":").append(m.id).append(",");
            sb.append("\"description\":\"").append(escape(m.description)).append("\",");
            sb.append("\"targetVolume\":").append(m.targetVolume).append(",");
            sb.append("\"achieved\":").append(m.achieved).append(",");
            sb.append("\"disbursed\":").append(m.disbursed);
            sb.append("}");
        }
        sb.append("],");
        // audit
        sb.append("\"audit\":[");
        for (int i=0;i<p.audit.size();i++){
            AuditEvent a = p.audit.get(i);
            if (i>0) sb.append(",");
            sb.append("{");
            sb.append("\"actor\":\"").append(escape(a.actor)).append("\",");
            sb.append("\"message\":\"").append(escape(a.message)).append("\"");
            sb.append("}");
        }
        sb.append("]");
        sb.append("}");
        return sb.toString();
    }

    static String listToJson(List<Project> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i=0;i<list.size();i++){
            if (i>0) sb.append(",");
            sb.append(projectToJson(list.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type","application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
