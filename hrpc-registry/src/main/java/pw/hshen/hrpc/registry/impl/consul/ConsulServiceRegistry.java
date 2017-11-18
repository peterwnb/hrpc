package pw.hshen.hrpc.registry.impl.consul;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.ConsulRawClient;
import com.ecwid.consul.v1.agent.model.NewService;
import org.apache.commons.codec.digest.DigestUtils;
import pw.hshen.hrpc.registry.ServiceRegistry;
import pw.hshen.hrpc.registry.model.ServiceAddress;

import java.util.ArrayList;

/**
 * @author hongbin
 * Created on 21/10/2017
 */
public class ConsulServiceRegistry implements ServiceRegistry {

	private ConsulClient consulClient;

	public ConsulServiceRegistry(String consulAddress) {
		String address[] = consulAddress.split(":");
		ConsulRawClient rawClient = new ConsulRawClient(address[0], Integer.valueOf(address[1]));
		consulClient = new ConsulClient(rawClient);
	}

	@Override
	public void register(String serviceName, ServiceAddress serviceAddress) {
		NewService newService = new NewService();
		newService.setId(generateNewIdForService(serviceName));
		newService.setName(serviceName);
		newService.setTags(new ArrayList<>());
		newService.setAddress(serviceAddress.getIp());
		newService.setPort(serviceAddress.getPort());

		NewService.Check check = new NewService.Check();
		check.setTcp(serviceAddress.toString());
		check.setInterval("1s");
		newService.setCheck(check);
		consulClient.agentServiceRegister(newService);
	}

	private String generateNewIdForService(String serviceName){
		// TODO: Confirm id is unique
		// serviceName + ip + port
		return serviceName + "-" + "";
	}
}
