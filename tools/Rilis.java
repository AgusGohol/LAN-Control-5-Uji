import java.nio.file.*;import java.security.*;import java.security.spec.*;import java.util.*;import java.util.jar.*;import java.util.regex.*;
/** Developer tool: signs LAN-Control-5.jar with the offline release key -> LAN-Control-5.jar.sig, then verifies it with the key built into the app.
 *  Usage (from the app folder): java tools/Rilis.java "<path>\lan5-release-private.key" */
public class Rilis {
    public static void main(String[] args)throws Exception{
        if(args.length!=1){System.err.println("Pakai: java tools/Rilis.java <file kunci privat>");System.exit(2);}
        byte[] jar=Files.readAllBytes(Path.of("LAN-Control-5.jar"));
        String version;try(JarFile j=new JarFile("LAN-Control-5.jar")){version=j.getManifest().getMainAttributes().getValue("Implementation-Version");}
        PrivateKey key=KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(Path.of(args[0])).trim())));
        Signature s=Signature.getInstance("Ed25519");s.initSign(key);s.update(jar);byte[] signature=s.sign();
        Matcher m=Pattern.compile("publicKey=\"([^\"]+)\"").matcher(Files.readString(Path.of("src","Updater.java")));if(!m.find())throw new Exception("Kunci publik tidak ditemukan di src/Updater.java");
        Signature v=Signature.getInstance("Ed25519");v.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(m.group(1)))));v.update(jar);
        if(!v.verify(signature))throw new Exception("Kunci privat tidak cocok dengan kunci publik aplikasi. Rilis dibatalkan.");
        Files.writeString(Path.of("LAN-Control-5.jar.sig"),Base64.getEncoder().encodeToString(signature)+"\n");
        System.out.println("Ditandatangani: LAN-Control-5.jar v"+version+" -> LAN-Control-5.jar.sig (terverifikasi)");
    }
}
