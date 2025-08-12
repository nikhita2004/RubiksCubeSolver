// RubiksCubeSolver.java
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/*
 Face order used everywhere: 0=U(W), 1=D(Y), 2=F(R), 3=B(O), 4=L(B), 5=R(G)
 States returned as states[s][face(0..5)][row(0..2)][col(0..2)].
 JSON includes: states, moves (reverse moves), scrambleLength, finalSolved (boolean), mismatches (array strings)
*/
public class RubiksCubeSolver {
    private static char[][][] cube = new char[6][3][3];
    private static List<char[][][]> states = new ArrayList<>();
    private static List<String> movesList = new ArrayList<>();
    private static final char[] FACE_COLORS = { 'W', 'Y', 'R', 'O', 'B', 'G' };
    private static final String[] FACE_NAMES = { "U","D","F","B","L","R" };

    public static void main(String[] args) throws Exception {
        resetCube();
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.createContext("/solve", RubiksCubeSolver::handleSolve);
        server.setExecutor(null);
        server.start();
        System.out.println("Server running at http://localhost:8000/solve?scramble=U%20R%20U'");
    }

    private static void handleSolve(HttpExchange exchange) throws IOException {
        try {
            String query = exchange.getRequestURI().getQuery();
            String scramble = "";
            if (query != null) {
                for (String part : query.split("&")) {
                    int idx = part.indexOf('=');
                    if (idx > 0) {
                        String key = part.substring(0, idx);
                        String val = part.substring(idx + 1);
                        if ("scramble".equals(key)) {
                            scramble = URLDecoder.decode(val, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
            scramble = scramble == null ? "" : scramble.trim().replaceAll("\\s+", " ");

            resetCube();
            states.clear();
            movesList.clear();
            storeState(); // index 0 = solved initial

            // apply scramble, storing each intermediate state
            List<String> scrambleMoves = new ArrayList<>();
            if (!scramble.isEmpty()) {
                scrambleMoves = Arrays.asList(scramble.split("\\s+"));
                for (String mv : scrambleMoves) {
                    applyMove(mv);
                    storeState();
                }
            }

            // reverse-solve: compute reverse moves and apply, storing states and move list
            List<String> reverse = reverseMoves(scrambleMoves);
            for (String mv : reverse) {
                applyMove(mv);
                movesList.add(mv);
                storeState();
            }

            // validate final cube
            boolean finalSolved = isSolved();
            List<String> mismatches = new ArrayList<>();
            if (!finalSolved) {
                // compare final cube to expected face center colors and collect mismatches
                for (int f = 0; f < 6; f++) {
                    for (int i = 0; i < 3; i++) {
                        for (int j = 0; j < 3; j++) {
                            if (cube[f][i][j] != FACE_COLORS[f]) {
                                mismatches.add(String.format("%s[%d,%d] expected=%c actual=%c",
                                        FACE_NAMES[f], i, j, FACE_COLORS[f], cube[f][i][j]));
                            }
                        }
                    }
                }
            }

            // build JSON and include scrambleLength & diagnostics
            int scrambleLen = scrambleMoves.size();
            String json = buildJsonWithMeta(scrambleLen, finalSolved, mismatches);

            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        } catch (Exception e) {
            String err = "{\"error\":\"" + e.getMessage().replace("\"","'") + "\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            byte[] out = err.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        }
    }

    // build JSON with states, moves, scrambleLength, finalSolved, mismatches
    private static String buildJsonWithMeta(int scrambleLen, boolean finalSolved, List<String> mismatches) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"states\":");
        sb.append(buildStatesJson());
        sb.append(",\"moves\":");
        sb.append(buildMovesJson());
        sb.append(",\"scrambleLength\":").append(scrambleLen);
        sb.append(",\"finalSolved\":").append(finalSolved);
        sb.append(",\"mismatches\":");
        sb.append("[");
        for (int i = 0; i < mismatches.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(mismatches.get(i).replace("\"","'")).append("\"");
        }
        sb.append("]");
        sb.append("}");
        return sb.toString();
    }

    private static String buildStatesJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int s = 0; s < states.size(); s++) {
            if (s > 0) sb.append(",");
            sb.append("[");
            for (int f = 0; f < 6; f++) {
                if (f > 0) sb.append(",");
                sb.append("[");
                for (int i = 0; i < 3; i++) {
                    if (i > 0) sb.append(",");
                    sb.append("[");
                    for (int j = 0; j < 3; j++) {
                        if (j > 0) sb.append(",");
                        sb.append("\"").append(states.get(s)[f][i][j]).append("\"");
                    }
                    sb.append("]");
                }
                sb.append("]");
            }
            sb.append("]");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String buildMovesJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int m = 0; m < movesList.size(); m++) {
            if (m > 0) sb.append(",");
            sb.append("\"").append(movesList.get(m)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private static void storeState() {
        char[][][] snap = new char[6][3][3];
        for (int f = 0; f < 6; f++) {
            for (int i = 0; i < 3; i++) snap[f][i] = cube[f][i].clone();
        }
        states.add(snap);
    }

    private static List<String> reverseMoves(List<String> moves) {
        List<String> rev = new ArrayList<>();
        for (int i = moves.size() - 1; i >= 0; i--) {
            String m = moves.get(i);
            if (m.endsWith("'")) rev.add(m.substring(0, m.length() - 1));
            else if (m.endsWith("2")) rev.add(m);
            else rev.add(m + "'");
        }
        return rev;
    }

    private static void resetCube() {
        for (int f = 0; f < 6; f++)
            for (int i = 0; i < 3; i++)
                Arrays.fill(cube[f][i], FACE_COLORS[f]);
    }

    // rotate helpers
    private static void rotateFaceClockwise(int face) {
        char[][] tmp = new char[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                tmp[i][j] = cube[face][2 - j][i];
        cube[face] = tmp;
    }
    private static void rotateFaceCounterClockwise(int face) {
        rotateFaceClockwise(face);
        rotateFaceClockwise(face);
        rotateFaceClockwise(face);
    }

    private static void applyMove(String move) {
        if (move == null || move.isEmpty()) return;
        switch (move) {
            case "U": rotateU(); break;
            case "U'": rotateUPrime(); break;
            case "D": rotateD(); break;
            case "D'": rotateDPrime(); break;
            case "F": rotateF(); break;
            case "F'": rotateFPrime(); break;
            case "B": rotateB(); break;
            case "B'": rotateBPrime(); break;
            case "L": rotateL(); break;
            case "L'": rotateLPrime(); break;
            case "R": rotateR(); break;
            case "R'": rotateRPrime(); break;
            case "U2": rotateU(); rotateU(); break;
            case "D2": rotateD(); rotateD(); break;
            case "F2": rotateF(); rotateF(); break;
            case "B2": rotateB(); rotateB(); break;
            case "L2": rotateL(); rotateL(); break;
            case "R2": rotateR(); rotateR(); break;
            default: /* ignore unknown tokens */ break;
        }
    }

    // Rotation implementations (same as before)
    private static void rotateU() {
        rotateFaceClockwise(0);
        char[] t = cube[2][0].clone();
        cube[2][0] = cube[5][0].clone();
        cube[5][0] = cube[3][0].clone();
        cube[3][0] = cube[4][0].clone();
        cube[4][0] = t;
    }
    private static void rotateUPrime() {
        rotateFaceCounterClockwise(0);
        char[] t = cube[2][0].clone();
        cube[2][0] = cube[4][0].clone();
        cube[4][0] = cube[3][0].clone();
        cube[3][0] = cube[5][0].clone();
        cube[5][0] = t;
    }

    private static void rotateD() {
        rotateFaceClockwise(1);
        char[] t = cube[2][2].clone();
        cube[2][2] = cube[4][2].clone();
        cube[4][2] = cube[3][2].clone();
        cube[3][2] = cube[5][2].clone();
        cube[5][2] = t;
    }
    private static void rotateDPrime() {
        rotateFaceCounterClockwise(1);
        char[] t = cube[2][2].clone();
        cube[2][2] = cube[5][2].clone();
        cube[5][2] = cube[3][2].clone();
        cube[3][2] = cube[4][2].clone();
        cube[4][2] = t;
    }

    private static void rotateF() {
        rotateFaceClockwise(2);
        char[] t = { cube[0][2][0], cube[0][2][1], cube[0][2][2] };
        for (int i=0;i<3;i++) cube[0][2][i] = cube[4][2 - i][2];
        for (int i=0;i<3;i++) cube[4][i][2] = cube[1][0][i];
        for (int i=0;i<3;i++) cube[1][0][i] = cube[5][2 - i][0];
        for (int i=0;i<3;i++) cube[5][i][0] = t[i];
    }
    private static void rotateFPrime() {
        rotateFaceCounterClockwise(2);
        char[] t = { cube[0][2][0], cube[0][2][1], cube[0][2][2] };
        for (int i=0;i<3;i++) cube[0][2][i] = cube[5][i][0];
        for (int i=0;i<3;i++) cube[5][i][0] = cube[1][0][2 - i];
        for (int i=0;i<3;i++) cube[1][0][i] = cube[4][i][2];
        for (int i=0;i<3;i++) cube[4][i][2] = t[2 - i];
    }

    private static void rotateB() {
        rotateFaceClockwise(3);
        char[] t = { cube[0][0][0], cube[0][0][1], cube[0][0][2] };
        for (int i=0;i<3;i++) cube[0][0][i] = cube[5][i][2];
        for (int i=0;i<3;i++) cube[5][i][2] = cube[1][2][2 - i];
        for (int i=0;i<3;i++) cube[1][2][i] = cube[4][i][0];
        for (int i=0;i<3;i++) cube[4][i][0] = t[2 - i];
    }
    private static void rotateBPrime() {
        rotateFaceCounterClockwise(3);
        char[] t = { cube[0][0][0], cube[0][0][1], cube[0][0][2] };
        for (int i=0;i<3;i++) cube[0][0][i] = cube[4][2 - i][0];
        for (int i=0;i<3;i++) cube[4][i][0] = cube[1][2][i];
        for (int i=0;i<3;i++) cube[1][2][i] = cube[5][2 - i][2];
        for (int i=0;i<3;i++) cube[5][i][2] = t[i];
    }

    private static void rotateL() {
    rotateFaceClockwise(4); // Left face
    char[] t = { cube[0][0][0], cube[0][1][0], cube[0][2][0] };
    for (int i=0;i<3;i++) cube[0][i][0] = cube[2][i][0];
    for (int i=0;i<3;i++) cube[2][i][0] = cube[1][i][0];
    for (int i=0;i<3;i++) cube[1][i][0] = cube[3][2 - i][2];
    for (int i=0;i<3;i++) cube[3][i][2] = t[2 - i];
}

private static void rotateLPrime() {
    rotateFaceCounterClockwise(4);
    char[] t = { cube[0][0][0], cube[0][1][0], cube[0][2][0] };
    for (int i=0;i<3;i++) cube[0][i][0] = cube[3][2 - i][2];
    for (int i=0;i<3;i++) cube[3][i][2] = cube[1][2 - i][0];
    for (int i=0;i<3;i++) cube[1][i][0] = cube[2][i][0];
    for (int i=0;i<3;i++) cube[2][i][0] = t[i];
}

private static void rotateR() {
    rotateFaceClockwise(5); // Right face
    char[] t = { cube[0][0][2], cube[0][1][2], cube[0][2][2] };
    for (int i=0;i<3;i++) cube[0][i][2] = cube[3][2 - i][0];
    for (int i=0;i<3;i++) cube[3][i][0] = cube[1][2 - i][2];
    for (int i=0;i<3;i++) cube[1][i][2] = cube[2][i][2];
    for (int i=0;i<3;i++) cube[2][i][2] = t[i];
}

private static void rotateRPrime() {
    rotateFaceCounterClockwise(5);
    char[] t = { cube[0][0][2], cube[0][1][2], cube[0][2][2] };
    for (int i=0;i<3;i++) cube[0][i][2] = cube[2][i][2];
    for (int i=0;i<3;i++) cube[2][i][2] = cube[1][i][2];
    for (int i=0;i<3;i++) cube[1][i][2] = cube[3][2 - i][0];
    for (int i=0;i<3;i++) cube[3][i][0] = t[2 - i];
}


    private static boolean isSolved() {
        for (int f = 0; f < 6; f++) {
            char expected = FACE_COLORS[f];
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    if (cube[f][i][j] != expected) return false;
        }
        return true;
    }
}
