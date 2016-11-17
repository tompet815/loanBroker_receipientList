package starter;

import recipientlist.RecipientList;


public class Starter {
       public static void main(String[] argv) throws Exception {
        RecipientList rlist = new RecipientList();
        rlist.connect();
        rlist.receive();
    }
}
