import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.Executors;

public class BrowserAutomationServer {
    private static final int PORT = 3000;
    private static BrowserController browserController = new BrowserController();
    
    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        
        // Route handlers
        server.createContext("/", new HomeHandler());
        server.createContext("/open", new OpenHandler());
        server.createContext("/close", new CloseHandler());
        server.createContext("/clear", new ClearHandler());
        server.createContext("/current-page", new CurrentPageHandler());
        
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();
        
        System.out.println("Browser Automation Server listening on port " + PORT);
    }
    
    // Parse query parameters from URL
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    try {
                        params.put(keyValue[0], URLDecoder.decode(keyValue[1], "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        params.put(keyValue[0], keyValue[1]);
                    }
                }
            }
        }
        return params;
    }
    
    // Send HTTP response
    private static void sendResponse(HttpExchange exchange, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(200, response.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }
    
    // Home page handler
    static class HomeHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println("home page req");
            sendResponse(exchange, "Browser Automation Server - Hello World!");
        }
    }
    
    // Open browser handler
    static class OpenHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println("/open req");
            URI requestURI = exchange.getRequestURI();
            Map<String, String> params = parseQuery(requestURI.getQuery());
            System.out.println("Query params: " + params);
            
            String browser = params.get("app");
            String url = params.get("url");
            
            BrowserResult result = browserController.openBrowser(browser, url);
            sendResponse(exchange, result.getStatus());
        }
    }
    
    // Close browser handler
    static class CloseHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println("/close req");
            URI requestURI = exchange.getRequestURI();
            Map<String, String> params = parseQuery(requestURI.getQuery());
            System.out.println("Query params: " + params);
            
            String browser = params.get("app");
            BrowserResult result = browserController.closeBrowser(browser);
            sendResponse(exchange, result.getStatus());
        }
    }
    
    // Clear cache handler
    static class ClearHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println("/clear req");
            URI requestURI = exchange.getRequestURI();
            Map<String, String> params = parseQuery(requestURI.getQuery());
            System.out.println("Query params: " + params);
            
            String browser = params.get("app");
            BrowserResult result = browserController.clearCacheAndHistory(browser);
            sendResponse(exchange, result.getStatus());
        }
    }
    
    // Current page handler
    static class CurrentPageHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println("/current-page req");
            URI requestURI = exchange.getRequestURI();
            Map<String, String> params = parseQuery(requestURI.getQuery());
            System.out.println("Query params: " + params);
            
            String browser = params.get("app");
            BrowserResult result = browserController.getCurrentPage(browser);
            sendResponse(exchange, result.getStatus());
        }
    }
}

// Browser result wrapper class
class BrowserResult {
    private int code;
    private String status;
    
    public BrowserResult(int code, String status) {
        this.code = code;
        this.status = status;
    }
    
    public int getCode() { return code; }
    public String getStatus() { return status; }
}

// Main browser controller class
class BrowserController {
    private Map<String, Process> browserProcesses = new HashMap<>();
    private String osName = System.getProperty("os.name").toLowerCase();
    
    public BrowserResult openBrowser(String browserInput, String url) {
        String status = "";
        String browserName = getBrowserName(browserInput);
        
        if (browserName == null) {
            status = "browser param invalid. Taking firefox as default browser.";
            System.out.println(status);
            browserName = "firefox";
        }
        
        if (url == null || url.isEmpty()) {
            System.err.println("Please enter a URL, e.g. \"http://www.browserstack.com\"");
            status += " invalid url";
            return new BrowserResult(0, status);
        }
        
        String command = buildOpenCommand(browserInput, url);
        if (command == null) {
            status += " No platform detected or unsupported";
            return new BrowserResult(0, status);
        }
        
        try {
            System.out.println("exec command: " + command);
            Process process = Runtime.getRuntime().exec(command);
            browserProcesses.put(browserInput, process);
            status += " Success execution";
            return new BrowserResult(1, status);
        } catch (IOException e) {
            status += " Failed to execute command: " + e.getMessage();
            return new BrowserResult(0, status);
        }
    }
    
    public BrowserResult closeBrowser(String browserInput) {
        String status = "browser killed";
        String browserName = getBrowserName(browserInput);
        
        if (browserName == null) {
            status = "browser param invalid.";
            System.out.println(status);
            return new BrowserResult(0, status);
        }
        
        System.out.println(":test: " + browserName + " kill");
        
        try {
            String killCommand = buildKillCommand(browserInput);
            if (killCommand != null) {
                Runtime.getRuntime().exec(killCommand);
            }
            
            // Also terminate stored process if exists
            Process process = browserProcesses.get(browserInput);
            if (process != null) {
                process.destroy();
                browserProcesses.remove(browserInput);
            }
            
        } catch (IOException e) {
            status += " Error: " + e.getMessage();
        }
        
        return new BrowserResult(1, status);
    }
    
    public BrowserResult clearCacheAndHistory(String browserInput) {
        String status = "Cache and history cleared";
        
        if (browserInput == null) {
            status = "browser param invalid.";
            return new BrowserResult(0, status);
        }
        
        try {
            // First close the browser to ensure files are not in use
            closeBrowser(browserInput);
            
            // Wait a moment for browser to fully close
            Thread.sleep(2000);
            
            if ("firefox".equals(browserInput)) {
                clearFirefoxData();
            } else if ("chrome".equals(browserInput)) {
                clearChromeData();
            } else {
                status = "Unsupported browser for cache clearing: " + browserInput;
                return new BrowserResult(0, status);
            }
            
        } catch (Exception e) {
            status = "Error clearing cache: " + e.getMessage();
            return new BrowserResult(0, status);
        }
        
        return new BrowserResult(1, status);
    }
    
    public BrowserResult getCurrentPage(String browserInput) {
        String status = "Current page detection not fully implemented";
        
        // This is a simplified implementation
        // In a real scenario, you might need to use browser debugging protocols
        // or read browser history/session files
        
        try {
            if ("firefox".equals(browserInput)) {
                status = getCurrentFirefoxPage();
            } else if ("chrome".equals(browserInput)) {
                status = getCurrentChromePage();
            } else {
                status = "Unsupported browser for page detection: " + browserInput;
            }
        } catch (Exception e) {
            status = "Error getting current page: " + e.getMessage();
        }
        
        return new BrowserResult(1, status);
    }
    
    private String getBrowserName(String browserInput) {
        if ("chrome".equals(browserInput)) return "Google Chrome";
        if ("firefox".equals(browserInput)) return "Firefox";
        return null;
    }
    
    private String buildOpenCommand(String browserInput, String url) {
        if (osName.contains("win")) {
            // Windows
            if ("chrome".equals(browserInput)) {
                return "cmd /c start chrome " + url;
            } else if ("firefox".equals(browserInput)) {
                return "cmd /c start firefox " + url;
            }
        } else if (osName.contains("mac")) {
            // macOS
            String browserName = getBrowserName(browserInput);
            return "open -a \"" + browserName + "\" " + url;
        } else {
            // Linux
            if ("chrome".equals(browserInput)) {
                return "google-chrome --no-sandbox " + url;
            } else if ("firefox".equals(browserInput)) {
                return "firefox " + url;
            }
        }
        return null;
    }
    
    private String buildKillCommand(String browserInput) {
        if (osName.contains("win")) {
            // Windows
            if ("chrome".equals(browserInput)) {
                return "taskkill /f /im chrome.exe";
            } else if ("firefox".equals(browserInput)) {
                return "taskkill /f /im firefox.exe";
            }
        } else {
            // Unix-like (macOS/Linux)
            if ("chrome".equals(browserInput)) {
                return "pkill -f chrome";
            } else if ("firefox".equals(browserInput)) {
                return "pkill -f firefox";
            }
        }
        return null;
    }
    
    private void clearFirefoxData() throws IOException {
        String homeDir = System.getProperty("user.home");
        
        if (osName.contains("win")) {
            // Windows Firefox paths
            String profilePath = homeDir + "\\AppData\\Roaming\\Mozilla\\Firefox\\Profiles";
            executeCommand("cmd /c del /s /q \"" + profilePath + "\\*\\*.sqlite\"");
            executeCommand("cmd /c del /s /q \"" + profilePath + "\\*\\sessionstore*\"");
            executeCommand("cmd /c rmdir /s /q \"" + homeDir + "\\AppData\\Local\\Mozilla\\Firefox\\Profiles\"");
        } else {
            // Unix-like systems
            executeCommand("rm -f " + homeDir + "/.mozilla/firefox/*default*/*.sqlite");
            executeCommand("rm -f " + homeDir + "/.mozilla/firefox/*default*/sessionstore.js");
            executeCommand("rm -rf " + homeDir + "/.cache/mozilla/firefox/*default*/*");
        }
    }
    
    private void clearChromeData() throws IOException {
        String homeDir = System.getProperty("user.home");
        
        if (osName.contains("win")) {
            // Windows Chrome paths
            String chromePath = homeDir + "\\AppData\\Local\\Google\\Chrome\\User Data\\Default";
            executeCommand("cmd /c del /q \"" + chromePath + "\\History*\"");
            executeCommand("cmd /c del /q \"" + chromePath + "\\Cache\\*\"");
            executeCommand("cmd /c del /q \"" + chromePath + "\\Cookies*\"");
        } else if (osName.contains("mac")) {
            // macOS Chrome paths
            String chromePath = homeDir + "/Library/Application Support/Google/Chrome/Default";
            executeCommand("rm -f \"" + chromePath + "/History*\"");
            executeCommand("rm -rf \"" + chromePath + "/Cache/*\"");
            executeCommand("rm -f \"" + chromePath + "/Cookies*\"");
        } else {
            // Linux Chrome paths
            String chromePath = homeDir + "/.config/google-chrome/Default";
            executeCommand("rm -f \"" + chromePath + "/History*\"");
            executeCommand("rm -rf \"" + chromePath + "/Cache/*\"");
            executeCommand("rm -f \"" + chromePath + "/Cookies*\"");
        }
    }
    
    private String getCurrentFirefoxPage() {
        // Simplified implementation - in reality, you'd need to read session files
        // or use Firefox's remote debugging protocol
        return "Firefox current page detection requires reading sessionstore.js or using debugging API";
    }
    
    private String getCurrentChromePage() {
        // Simplified implementation - in reality, you'd need to use Chrome DevTools Protocol
        return "Chrome current page detection requires Chrome DevTools Protocol or reading session files";
    }
    
    private void executeCommand(String command) throws IOException {
        System.out.println("Executing: " + command);
        Runtime.getRuntime().exec(command);
    }
}