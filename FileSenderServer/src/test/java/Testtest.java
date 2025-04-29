
public class Testtest {
    public static void main(String[] args) throws Exception {
        ProcessBuilder pm = new ProcessBuilder("java", "-cp", "../../../out/production/FileSenderServer", "Server");
        pm.inheritIO();
        Process p = pm.start();
        
        p.waitFor();
//Process pm = ProcessBuilder("java", "-cp", "...out etc", "Server");
//java -cp ../../../out/production/FileSenderServer Server
    }
}
