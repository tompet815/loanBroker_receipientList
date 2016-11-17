package recipientlist;

import models.Data;
import models.DataFromGetBanks;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import connector.RabbitMQConnector;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.namespace.QName;
import models.Bank;
import utilities.MessageUtility;

public class RecipientList {

    private final RabbitMQConnector connector = new RabbitMQConnector();
    private Channel channel;
    private String queueName;
    private final String EXCHANGENAME = "whatRecipientList";
    private final MessageUtility util = new MessageUtility();

    public void connect() throws IOException {
        channel = connector.getChannel();
        channel.exchangeDeclare(EXCHANGENAME, "direct");
        queueName = channel.queueDeclare().getQueue();
        channel.queueBind(queueName, EXCHANGENAME, "");
    }

    public boolean receive() throws IOException {

        System.out.println(" [*] Waiting for messages.");
        final Consumer consumer = new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties properties, byte[] body) throws IOException {
                System.out.println(" [x] Received ");
                try {
                    send(properties, body);
                }
                catch (JAXBException ex) {
                    Logger.getLogger(RecipientList.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        };
        channel.basicConsume(queueName, true, consumer);
        return true;
    }

    private DataFromGetBanks unmarchal(String bodyString) throws JAXBException {
        JAXBContext jc = JAXBContext.newInstance(DataFromGetBanks.class);
        Unmarshaller unmarchaller = jc.createUnmarshaller();
        StringReader reader = new StringReader(bodyString);
        return (DataFromGetBanks) unmarchaller.unmarshal(reader);
    }

    private String marchal(Data d) throws JAXBException {
        JAXBContext jc2 = JAXBContext.newInstance(Data.class);
        Marshaller marshaller = jc2.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        JAXBElement<Data> je2 = new JAXBElement(new QName("Data"), Data.class, d);
        StringWriter sw = new StringWriter();
        marshaller.marshal(je2, sw);

        return removeBom(sw.toString());
    }

    private String removeBom(String xmlString) {
        String res = xmlString.trim();
        return res.substring(res.indexOf("<?xml"));
    }

    public boolean send(BasicProperties prop, byte[] body) throws IOException, JAXBException {
        String bodyString = removeBom(new String(body));
        DataFromGetBanks data = unmarchal(bodyString);
        Data d = new Data(data.getSsn(), data.getCreditScore(), data.getLoanAmoount(), data.getLoanDuration());
        List<Bank> bankExchangeNames = data.getBankExchangeNames();
        for (Bank bank : bankExchangeNames) {
            String translatorExchangeName = "whatTranslator." + bank.getType();
            String xmlString = marchal(d);
            body = util.serializeBody(xmlString);
            
            System.out.println("sending from rl: " + xmlString);
            channel.basicPublish(translatorExchangeName, "", prop, body);

            //somehow tell Aggregator how many messages
        }
        return true;
    }


}
