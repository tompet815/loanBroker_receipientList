package recipientlist;

import models.Data;
import models.DataFromGetBanks;
import com.rabbitmq.client.AMQP.*;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import connector.RabbitMQConnector;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private Map<String, String> bankExchanges;

    //initialize RecipientList
    public void init() throws IOException {
        bankExchanges = new HashMap();
        bankExchanges.put("json", "whatTranslator.json");
        bankExchanges.put("xml", "whatTranslator.xml");
        bankExchanges.put("ws", "whatTranslator.ws");

        channel = connector.getChannel();
        channel.exchangeDeclare(EXCHANGENAME, "direct");
        queueName = channel.queueDeclare().getQueue();
        channel.queueBind(queueName, EXCHANGENAME, "");
        receive();
    }

    //Waiting asynchronously for messages
    public boolean receive() throws IOException {
        channel.basicQos(1);
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
                finally {
                    System.out.println(" [x] Done");
                    channel.basicAck(envelope.getDeliveryTag(), false);
                }
            }
        };
        channel.basicConsume(queueName, false, consumer);
        return true;
    }

    //unmarshal from string to Object
    private DataFromGetBanks unmarchal(String bodyString) throws JAXBException {
        JAXBContext jc = JAXBContext.newInstance(DataFromGetBanks.class);
        Unmarshaller unmarshaller = jc.createUnmarshaller();
        StringReader reader = new StringReader(bodyString);
        return (DataFromGetBanks) unmarshaller.unmarshal(reader);
    }

    //marshal from pbkect to xml string
    private String marchal(Data d) throws JAXBException {
        JAXBContext jc2 = JAXBContext.newInstance(Data.class);
        Marshaller marshaller = jc2.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        JAXBElement<Data> je2 = new JAXBElement(new QName("Data"), Data.class, d);
        StringWriter sw = new StringWriter();
        marshaller.marshal(je2, sw);

        return removeBom(sw.toString());
    }

    //remove unnecessary charactors before xml declaration 
    private String removeBom(String xmlString) {
        String res = xmlString.trim();
        return res.substring(res.indexOf("<?xml"));
    }

    //build a new property for messaging
    private BasicProperties propBuilder(String corrId, Map<String, Object> headers) {
        BasicProperties.Builder builder = new BasicProperties.Builder();
        builder.correlationId(corrId);
        builder.headers(headers);
        BasicProperties prop = builder.build();
        return prop;
    }

    //send message to exchange
    public boolean send(BasicProperties prop, byte[] body) throws IOException, JAXBException {
        //creating data for sending
        String corrId = prop.getCorrelationId();
        String bodyString = removeBom(new String(body));
        DataFromGetBanks data = unmarchal(bodyString);
        Data d = new Data(data.getSsn(), data.getCreditScore(), data.getLoanAmount(), data.getLoanDuration());

        List<Bank> bankExchangeNames = data.getBankExchangeNames();
        int totalBankAmount = bankExchangeNames.size();
        int count = 1;
        //send message to each bank in the banklist. 
        for (Bank bank : bankExchangeNames) {
            //creating headers that should be passed to Aggregator
            Map<String, Object> headers = new HashMap();
            headers.put("bankName", bank.getBankName());
            headers.put("total", totalBankAmount);
            headers.put("messageNo", count);
            headers.put("messageId", prop.getMessageId());
            
            String translatorExchangeName = bankExchanges.get(bank.getType());
            String xmlString = marchal(d);

            body = util.serializeBody(xmlString);
            BasicProperties newprop = propBuilder(corrId, headers);
            System.out.println("sending from rl to " + translatorExchangeName + " : " + xmlString);
            channel.basicPublish(translatorExchangeName, "", newprop, body);
            count++;
        }
        return true;
    }
}
