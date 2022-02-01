
package cmsc433.p4.actors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import cmsc433.p4.enums.*;
import cmsc433.p4.messages.*;
import cmsc433.p4.util.*;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.AbstractActor.Receive;
import akka.actor.AbstractActor;

public class ResourceManagerActor extends AbstractActor {
	
	private ActorRef logger;																			// Actor to send logging messages to
	private ArrayList<ActorRef> managers;																// ArrayList containing all managers on the network, including this one
	private ArrayList<ActorRef> localUsers;																// ArrayList containing all local users who will use this manager
	private ArrayList<Resource> localResources; 														// ArrayList containing all local resources for this manager
	private ArrayList<String> localResourcesNames; 														// ArrayList containing all local resources for this manager, but in name/String form
	private HashMap<String, Resource> localNameToResource; 												// Maps names of resources to resources
	private HashMap<String, ActorRef> foreignResourcesToManagers;										// maps names of foreign resources to their managers
	
	/* Lists of data structures for each individual resource */
	private HashMap<String, LinkedList<AccessRequestMsg>> requestQueues;								// Maps resource to a LinkedList of blocking requests
	private HashMap<String, LinkedList<ActorRef>> readers;												// Maps resource to a LinkedList of UserActors with read access
	private HashMap<String, LinkedList<ActorRef>> writers;												// Maps resource to a LinkedList of UserActors with write access
	private HashMap<String, ResourceStatus> statuses;													// Maps resource to status of resource
	private HashMap<String, HashMap<ActorRef, ManagementRequestMsg>> usersAwaitingDisableConfirm; 		// Maps resource to a LinkedList of UserActors awaiting confirmation of this resources' disablement
	private HashMap<String, HashMap<ActorRef, Integer>> writeReentrantManager;							// Maps resource to map of Users to Integers to keep track of re-entrancy
	private HashMap<String, HashMap<ActorRef, Integer>> readReentrantManager;							// Maps resource to map of Users to Integers to keep track of re-entrancy
	private HashMap<String, Integer> responseCount = new HashMap<String, Integer>();					
	private HashMap<String, LinkedList<Object>> foreignWaitingRequests;
	private LinkedList<String> nonexistant = new LinkedList<>();
	
	/**
	 * Props structure-generator for this class.
	 * @return  Props structure
	 */
	static Props props (ActorRef logger) {
		return Props.create(ResourceManagerActor.class, logger);
	}
	
	/**
	 * Factory method for creating resource managers
	 * @param logger			Actor to send logging messages to
	 * @param system			Actor system in which manager will execute
	 * @return					Reference to new manager
	 */
	public static ActorRef makeResourceManager (ActorRef logger, ActorSystem system) {
		ActorRef newManager = system.actorOf(props(logger));
		return newManager;
	}
	
	/**
	 * Sends a message to the Logger Actor
	 * @param msg The message to be sent to the logger
	 */
	public void log (LogMsg msg) {
		logger.tell(msg, getSelf());
	}
	
	/**
	 * Constructor
	 * 
	 * @param logger			Actor to send logging messages to
	 */
	private ResourceManagerActor(ActorRef logger) {
		super();
		this.logger = logger;
	}
	
	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.match(Object.class, this::onReceive)
				.build();
	}

	// You may want to add data structures for managing local resources and users, storing
	// remote managers, etc.
	//
	// REMEMBER:  YOU ARE NOT ALLOWED TO CREATE MUTABLE DATA STRUCTURES THAT ARE SHARED BY
	// MULTIPLE ACTORS!
	
	/* (non-Javadoc)
	 * 
	 * You must provide an implementation of the onReceive() method below.
	 * 
	 * @see akka.actor.AbstractActor#createReceive
	 */
	
	public void onReceive(Object msg) throws Exception {
		ActorRef replyTo = getSender();
		if(msg instanceof AddRemoteManagersRequestMsg) {
			recvAddRemoteManagersRequestMsg((AddRemoteManagersRequestMsg) msg, replyTo);
		} else if(msg instanceof AddLocalUsersRequestMsg) {
			recvAddLocalUsersRequestMsg((AddLocalUsersRequestMsg) msg, replyTo);
		} else if(msg instanceof AddInitialLocalResourcesRequestMsg) {
			recvAddInitialLocalResourcesRequestMsg((AddInitialLocalResourcesRequestMsg) msg, replyTo);
		} else if(msg instanceof AccessRequestMsg) {
			recvAccessRequestMsg((AccessRequestMsg) msg);
		} else if(msg instanceof ManagementRequestMsg) {
			recvManagementRequestMsg((ManagementRequestMsg) msg);
		} else if(msg instanceof AccessReleaseMsg) {
			recvAccessReleaseMsg((AccessReleaseMsg) msg);
		} else if(msg instanceof WhoHasResourceRequestMsg) {
			recvWhoHasResourceRequestMsg((WhoHasResourceRequestMsg) msg, replyTo);
		} else if(msg instanceof WhoHasResourceResponseMsg) {
			recvWhoHasResourceResponseMsg((WhoHasResourceResponseMsg) msg, replyTo);
		}
	}
	
	/* MESSAGE HANDLING METHODS */
	
	
	/* Handles AddRemoteManagersRequestMsg */
	private void recvAddRemoteManagersRequestMsg(AddRemoteManagersRequestMsg msg, ActorRef replyTo) {
		ArrayList<ActorRef> passedList = msg.getManagerList(); //gets list from msg
		managers = new ArrayList<ActorRef>(); //initialize managers ArrayList to store managers
		
		/* Loop to add managers to managers ArrayList */
		for(ActorRef man : passedList) {
			managers.add(man); // adds manager to list
		}
		
		/* Responding */
		AddRemoteManagersResponseMsg response = new AddRemoteManagersResponseMsg(msg); //initializing response message
		replyTo.tell(response, getSelf()); //sending response message
	}
	
	
	/* Handles AddLocalUsersRequestMsg */
	private void recvAddLocalUsersRequestMsg(AddLocalUsersRequestMsg msg, ActorRef replyTo) {
		ArrayList<ActorRef> passedList = msg.getLocalUsers(); //gets list from msg
		localUsers = new ArrayList<ActorRef>(); //initialize localUsers ArrayList to store managers
		
		/* Loop to add users to localUsers ArrayList */
		for(ActorRef user : passedList) {
			localUsers.add(user); // adds user to list
		}
		
		/* Responding */
		AddLocalUsersResponseMsg response = new AddLocalUsersResponseMsg(msg); //initializing response message
		replyTo.tell(response, getSelf()); //sending response message
	}
	
	
	/* Handles AddInitialLocalResourcesRequestMsg */
	private void recvAddInitialLocalResourcesRequestMsg(AddInitialLocalResourcesRequestMsg msg, ActorRef replyTo) {
		ArrayList<Resource> passedList = msg.getLocalResources(); 										//gets list from msg
		localResources = new ArrayList<Resource>(); 													//initialize localResources ArrayList to store local resources
		localResourcesNames = new ArrayList<String>(); 													//initialize localResourcesNames ArrayList to store 
		requestQueues = new HashMap<String, LinkedList<AccessRequestMsg>>(); 							//initialize requestQueues
		readers = new HashMap<String, LinkedList<ActorRef>>(); 											//initialize readers
		writers = new HashMap<String, LinkedList<ActorRef>>(); 											//intialize writers
		statuses = new HashMap<String, ResourceStatus>(); 												//initialize statuses
		usersAwaitingDisableConfirm = new HashMap<String, HashMap<ActorRef, ManagementRequestMsg>>();	//initialize usersAwaitingDisableConfirm
		writeReentrantManager = new HashMap<String, HashMap<ActorRef, Integer>>();						//initialize writeReentrantManager
		readReentrantManager = new HashMap<String, HashMap<ActorRef, Integer>>();						//initialize readReentrantManager
		localNameToResource = new HashMap<String, Resource>();											
		foreignResourcesToManagers = new HashMap<String, ActorRef>();
		foreignWaitingRequests = new HashMap<String, LinkedList<Object>>();
		
		/* Loop to add resources to localResources ArrayList And initialize data structures */
		for(Resource resource : passedList) {												
			localResources.add(resource); // adds resource to list
			localResourcesNames.add(resource.getName()); // adds resource name to list
			
			LogMsg x = LogMsg.makeLocalResourceCreatedLogMsg(getSelf(), resource.getName());
			System.out.println(x);
			log(x);	 // logging
			
			/* Initializing Data Structures for resource */
			requestQueues.put(resource.getName(), new LinkedList<AccessRequestMsg>());									// Creates queue for blocking requests
			readers.put(resource.getName(), new LinkedList<ActorRef>());											// Creates blank list of readers on this resource
			writers.put(resource.getName(), new LinkedList<ActorRef>());											// Creates blank list of writers on this resource
			statuses.put(resource.getName(), ResourceStatus.ENABLED);												// Creates map for resource status (enable/disable)
			usersAwaitingDisableConfirm.put(resource.getName(), new HashMap<ActorRef, ManagementRequestMsg>());						// Creates blank list of Users to notify of disablement
			writeReentrantManager.put(resource.getName(), new HashMap<ActorRef, Integer>());  						// Creates HashMaps for reentrant access
			readReentrantManager.put(resource.getName(), new HashMap<ActorRef, Integer>());  						// Creates HashMaps for reentrant access
			localNameToResource.put(resource.getName(), resource);
			resource.enable(); 																						// enables resource
			
			x = LogMsg.makeResourceStatusChangedLogMsg(getSelf(), resource.getName(), ResourceStatus.ENABLED);
			System.out.println(x);
			log(x);		// log
		}
		
		/* Responding */
		AddInitialLocalResourcesResponseMsg response = new AddInitialLocalResourcesResponseMsg(msg); //initializing response message
		replyTo.tell(response, getSelf()); //sending response message
	}
	
	
	/* Handles AccessRequestMsg */
	private void recvAccessRequestMsg(AccessRequestMsg msg) {
		String resourceName = msg.getAccessRequest().getResourceName(); // Stores name of resource
		ActorRef replyTo = msg.getReplyTo(); // Stores sender
		
		LogMsg x = LogMsg.makeAccessRequestReceivedLogMsg(replyTo, getSelf(), msg.getAccessRequest());
		System.out.println(x);
		log(x); // log
		
		/* RESOURCE IS LOCAL, HANDLE REQUEST LOCALLY */
		if(localResourcesNames.contains(resourceName)) {
			if(statuses.get(resourceName).equals(ResourceStatus.ENABLED)) { // Checks if resource is enabled
				/* This block of code is when the resource is handled by this manager and is enabled, the main management of resources is done here */
				AccessRequestType msgType = msg.getAccessRequest().getType(); // Holds AccessRequestType of message
				
				/* EXCLUSIVE_WRITE_NONBLOCKING */
				if(msgType.equals(AccessRequestType.EXCLUSIVE_WRITE_NONBLOCKING)) {	
					if(writers.get(resourceName).size() == 1 && writers.get(resourceName).contains(replyTo)) { // For re-entrant access, when writing access already owned
						Integer oldAccesses = writeReentrantManager.get(resourceName).get(replyTo); //getting old # of accesses
						writeReentrantManager.get(resourceName).put(replyTo, oldAccesses + 1); //Setting # of accesses to old + 1
						
						AccessRequestGrantedMsg response = new AccessRequestGrantedMsg(msg.getAccessRequest()); // Creating AccessRequestGrantedMsg to respond
						
						x = LogMsg.makeAccessRequestGrantedLogMsg(replyTo, getSelf(), msg.getAccessRequest());
						System.out.println(x);
						log(x); // log
						
						replyTo.tell(response, getSelf()); // Sending
					} else if(writers.get(resourceName).size() == 0 && readers.get(resourceName).size() == 1 && readers.get(resourceName).contains(replyTo)) { // For re-entrant access, when holding read exclusively we can write
						writers.get(resourceName).add(replyTo); // Adding
						
						//Integer oldAccesses = writeReentrantManager.get(resourceName).get(replyTo);
						writeReentrantManager.get(resourceName).put(replyTo, 1); //Setting # of accesses to 1
						AccessRequestGrantedMsg response = new AccessRequestGrantedMsg(msg.getAccessRequest()); // Creating AccessRequestGrantedMsg to respond
						
						x = LogMsg.makeAccessRequestGrantedLogMsg(replyTo, getSelf(), msg.getAccessRequest());
						System.out.println(x);
						log(x); // log
						
						replyTo.tell(response, getSelf()); // Sending
					} else if(writers.get(resourceName).size() == 0 && readers.get(resourceName).size() == 0) { // If no writers or readers, we can write, so send confirmation message
						writers.get(resourceName).add(replyTo); // Adding
						
						writeReentrantManager.get(resourceName).put(replyTo, 1); //Setting # of accesses to 1
						AccessRequestGrantedMsg response = new AccessRequestGrantedMsg(msg.getAccessRequest()); // Creating AccessRequestGrantedMsg to respond
						
						x = LogMsg.makeAccessRequestGrantedLogMsg(replyTo, getSelf(), msg.getAccessRequest());
						System.out.println(x);
						log(x); // log
						
						replyTo.tell(response, getSelf()); // Sending
					} else { // Resource busy
						AccessRequestDeniedMsg response = new AccessRequestDeniedMsg(msg.getAccessRequest(), AccessRequestDenialReason.RESOURCE_BUSY); // Creating AccessRequestDeniedMsg to respond
						
						x = LogMsg.makeAccessRequestDeniedLogMsg(replyTo, getSelf(), msg.getAccessRequest(), AccessRequestDenialReason.RESOURCE_BUSY);
						System.out.println(x);
						log(x); // log
						
						replyTo.tell(response, getSelf()); // Sending
					}
				}
				
				/* CONCURRENT_READ_NONBLOCKING */
				else if(msgType.equals(AccessRequestType.CONCURRENT_READ_NONBLOCKING)) {
					if(readers.get(resourceName).contains(replyTo)) { // For re-entrant access, can give read access if already holding read access
						Integer oldAccesses = readReentrantManager.get(resourceName).get(replyTo);
						readReentrantManager.get(resourceName).put(replyTo, oldAccesses + 1);
						
						AccessRequestGrantedMsg response = new AccessRequestGrantedMsg(msg.getAccessRequest()); // Creating AccessRequestGrantedMsg to respond
						
						x = LogMsg.makeAccessRequestGrantedLogMsg(replyTo, getSelf(), msg.getAccessRequest());
						System.out.println(x);
						log(x); // log
						
						replyTo.tell(response, getSelf()); // Sending
					} else if(writers.get(resourceName).size() == 0) { // If no writers, we can read, so send confirmation message
						readers.get(resourceName).add(replyTo); // Adding
						
						readReentrantManager.get(resourceName).put(replyTo, 1);
						
						AccessRequestGrantedMsg response = new AccessRequestGrantedMsg(msg.getAccessRequest()); // Creating AccessRequestGrantedMsg to respond
						x = LogMsg.makeAccessRequestGrantedLogMsg(replyTo, getSelf(), msg.getAccessRequest());
						System.out.println(x);
						log(x); // log
						
						replyTo.tell(response, getSelf()); // Sending
					} else if(writers.get(resourceName).contains(replyTo)) { // For re-entrant access, can give read access if holding write access
						readers.get(resourceName).add(replyTo); // Adding
						
						//Integer oldAccesses = readReentrantManager.get(resourceName).get(replyTo);
						readReentrantManager.get(resourceName).put(replyTo, 1);
						
						AccessRequestGrantedMsg response = new AccessRequestGrantedMsg(msg.getAccessRequest()); // Creating AccessRequestGrantedMsg to respond
						x = LogMsg.makeAccessRequestGrantedLogMsg(replyTo, getSelf(), msg.getAccessRequest());
						System.out.println(x);
						log(x); // log
						
						replyTo.tell(response, getSelf()); // Sending
					} else { // Resource busy
						AccessRequestDeniedMsg response = new AccessRequestDeniedMsg(msg.getAccessRequest(), AccessRequestDenialReason.RESOURCE_BUSY); // Creating AccessRequestDeniedMsg to respond
						
						x = LogMsg.makeAccessRequestDeniedLogMsg(replyTo, getSelf(), msg.getAccessRequest(), AccessRequestDenialReason.RESOURCE_BUSY);
						System.out.println(x);
						log(x); // log
						
						replyTo.tell(response, getSelf()); // Sending
					}
				}
				
				/* EXCLUSIVE_WRITE_BLOCKING */
				else if(msgType.equals(AccessRequestType.EXCLUSIVE_WRITE_BLOCKING)) {
					if(writers.get(resourceName).size() == 1 && writers.get(resourceName).contains(replyTo)) { // For re-entrant access, when writing access already owned
						Integer oldAccesses = writeReentrantManager.get(resourceName).get(replyTo);
						writeReentrantManager.get(resourceName).put(replyTo, oldAccesses + 1); //Setting # of accesses to old + 1
						
						AccessRequestGrantedMsg response = new AccessRequestGrantedMsg(msg.getAccessRequest()); // Creating AccessRequestGrantedMsg to respond
						
						x = LogMsg.makeAccessRequestGrantedLogMsg(replyTo, getSelf(), msg.getAccessRequest());
						System.out.println(x);
						log(x); // log
						
						replyTo.tell(response, getSelf()); // Sending
					} else if(writers.get(resourceName).size() == 0 && readers.get(resourceName).size() == 1 && readers.get(resourceName).contains(replyTo)) { // For re-entrant access, when holding read exclusively we can write
						writers.get(resourceName).add(replyTo); // Adding
						
						//Integer oldAccesses = writeReentrantManager.get(resourceName).get(replyTo);
						writeReentrantManager.get(resourceName).put(replyTo, 1); //Setting # of accesses to 1
						AccessRequestGrantedMsg response = new AccessRequestGrantedMsg(msg.getAccessRequest()); // Creating AccessRequestGrantedMsg to respond
						
						x = LogMsg.makeAccessRequestGrantedLogMsg(replyTo, getSelf(), msg.getAccessRequest());
						System.out.println(x);
						log(x); // log
						
						replyTo.tell(response, getSelf()); // Sending
					} else if(writers.get(resourceName).size() == 0 && readers.get(resourceName).size() == 0) { // If no writers or readers, we can write, so send confirmation message
						writers.get(resourceName).add(replyTo); // Adding
						
						writeReentrantManager.get(resourceName).put(replyTo, 1); //Setting # of accesses to 1
						AccessRequestGrantedMsg response = new AccessRequestGrantedMsg(msg.getAccessRequest()); // Creating AccessRequestGrantedMsg to respond
						
						x = LogMsg.makeAccessRequestGrantedLogMsg(replyTo, getSelf(), msg.getAccessRequest());
						System.out.println(x);
						log(x); // log
						
						replyTo.tell(response, getSelf()); // Sending
					} else { // Resource busy
						requestQueues.get(resourceName).offer(msg); // Adding request to resource queue in FIFO order
					}
				}
				
				/* CONCURRENT_READ_BLOCKING */
				else if(msgType.equals(AccessRequestType.CONCURRENT_READ_BLOCKING)) {
					if(readers.get(resourceName).contains(replyTo)) { // For re-entrant access, can give read access if already holding read access
						Integer oldAccesses = readReentrantManager.get(resourceName).get(replyTo);
						readReentrantManager.get(resourceName).put(replyTo, oldAccesses + 1);
						
						AccessRequestGrantedMsg response = new AccessRequestGrantedMsg(msg.getAccessRequest()); // Creating AccessRequestGrantedMsg to respond
						
						x = LogMsg.makeAccessRequestGrantedLogMsg(replyTo, getSelf(), msg.getAccessRequest());
						System.out.println(x);
						log(x); // log
						
						replyTo.tell(response, getSelf()); // Sending
					} else if(writers.get(resourceName).size() == 0) { // If no writers, we can read, so send confirmation message
						readers.get(resourceName).add(replyTo); // Adding
						
						readReentrantManager.get(resourceName).put(replyTo, 1);
						
						AccessRequestGrantedMsg response = new AccessRequestGrantedMsg(msg.getAccessRequest()); // Creating AccessRequestGrantedMsg to respond
						
						x = LogMsg.makeAccessRequestGrantedLogMsg(replyTo, getSelf(), msg.getAccessRequest());
						System.out.println(x);
						log(x); // log
						
						replyTo.tell(response, getSelf()); // Sending
					} else if(writers.get(resourceName).contains(replyTo)) { // For re-entrant access, can give read access if holding write access
						readers.get(resourceName).add(replyTo); // Adding
						
						//Integer oldAccesses = readReentrantManager.get(resourceName).get(replyTo);
						readReentrantManager.get(resourceName).put(replyTo, 1);
						
						AccessRequestGrantedMsg response = new AccessRequestGrantedMsg(msg.getAccessRequest()); // Creating AccessRequestGrantedMsg to respond
						x = LogMsg.makeAccessRequestGrantedLogMsg(replyTo, getSelf(), msg.getAccessRequest());
						System.out.println(x);
						log(x); // log
						
						replyTo.tell(response, getSelf()); // Sending
					} else { // Resource busy
						requestQueues.get(resourceName).offer(msg); // Adding request to resource queue in FIFO order
					}
				}
			} else { // Here, the resource was disabled and an AccessRequestDeniedMsg must be sent back
				AccessRequestDeniedMsg response = new AccessRequestDeniedMsg(msg.getAccessRequest(), AccessRequestDenialReason.RESOURCE_DISABLED); // Creating AccessRequestDeniedMsg
				x = LogMsg.makeAccessRequestDeniedLogMsg(replyTo, getSelf(), msg.getAccessRequest(), AccessRequestDenialReason.RESOURCE_DISABLED);
				System.out.println(x);
				log(x); // log
				
				replyTo.tell(response, getSelf()); // Sending m
			}
		}
		
		/* RESOURCE IS NOT LOCAL, MUST FORWARD OR FIND RESOURCE */
		else {
			if(nonexistant.contains(resourceName)) {
				x = LogMsg.makeAccessRequestDeniedLogMsg(msg.getReplyTo(), getSelf(), msg.getAccessRequest(), AccessRequestDenialReason.RESOURCE_NOT_FOUND);
				System.out.println(x);
				log(x); // log
				
				AccessRequestDeniedMsg toSend = new AccessRequestDeniedMsg(msg.getAccessRequest(), AccessRequestDenialReason.RESOURCE_NOT_FOUND);
				msg.getReplyTo().tell(toSend, getSelf());
			} else if(foreignResourcesToManagers.containsKey(resourceName)) { // Checks if we know where the resource is, then we can just forward directly
				
				x = LogMsg.makeAccessRequestForwardedLogMsg(getSelf(), foreignResourcesToManagers.get(resourceName), msg.getAccessRequest());
				System.out.println(x);
				log(x); // log
				
				foreignResourcesToManagers.get(resourceName).tell(msg, getSelf()); // Forwarding message to foreign resource
			} else if(!foreignResourcesToManagers.containsKey(resourceName) && responseCount.containsKey(resourceName)) { // Being searched for already, not found yet
				foreignWaitingRequests.get(resourceName).add(msg);
			} else { // Send WhoHasResourceRequestMsg's
				responseCount.put(resourceName, managers.size());
				foreignWaitingRequests.put(resourceName, new LinkedList<Object>());
				foreignWaitingRequests.get(resourceName).add(msg);
				for(ActorRef manager : managers) {
					WhoHasResourceRequestMsg req = new WhoHasResourceRequestMsg(resourceName);
					manager.tell(req, getSelf());
				}
			}
		}
	}
	
	
	/* Handles ManagementRequestMsg */
	private void recvManagementRequestMsg(ManagementRequestMsg msg) {
		String resourceName = msg.getRequest().getResourceName(); // Stores name of resource
		ActorRef replyTo = msg.getReplyTo(); // Stores sender
		
		LogMsg xy = LogMsg.makeManagementRequestReceivedLogMsg(replyTo, getSelf(), msg.getRequest());
		System.out.println(xy);
		log(xy);
		
		/* RESOURCE IS LOCAL, HANDLE REQUEST LOCALLY */
		if(localResourcesNames.contains(resourceName)) {
			/* Handles disable requests */
			if(msg.getRequest().getType().equals(ManagementRequestType.DISABLE)) {
				if(localNameToResource.get(resourceName).getStatus().equals(ResourceStatus.DISABLED)) { // Saying accepted if already COMPLETELY disabled
					ManagementRequestGrantedMsg response = new ManagementRequestGrantedMsg(msg.getRequest());
					
					LogMsg x = LogMsg.makeManagementRequestGrantedLogMsg(replyTo, getSelf(), msg.getRequest());
					System.out.println(x);
					log(x); // log
					
					replyTo.tell(response, getSelf());
				} else if(statuses.get(resourceName).equals(ResourceStatus.DISABLED) && localNameToResource.get(resourceName).getStatus().equals(ResourceStatus.ENABLED)) { // Pending disablement, add to queue
					usersAwaitingDisableConfirm.get(resourceName).put(replyTo, msg); // adding user to list of users waiting on disablement
				} else if(readers.get(resourceName).contains(replyTo) || writers.get(resourceName).contains(replyTo)) { // If user holds access, deny request
					ManagementRequestDeniedMsg response = new ManagementRequestDeniedMsg(msg.getRequest(), ManagementRequestDenialReason.ACCESS_HELD_BY_USER);
					
					LogMsg x = LogMsg.makeManagementRequestDeniedLogMsg(replyTo, getSelf(), msg.getRequest(), ManagementRequestDenialReason.ACCESS_HELD_BY_USER);
					System.out.println(x);
					log(x); // log
					
					replyTo.tell(response, getSelf());
				} else { // Now we must actually handle request
					statuses.put(resourceName, ResourceStatus.DISABLED); // Setting to disabled in statuses
					
					//Iterating through blocked requests for this resource and sending them denied messages
					while(!(requestQueues.get(resourceName).isEmpty())) {
						AccessRequestMsg curr = requestQueues.get(resourceName).poll();
						AccessRequestDeniedMsg notification = new AccessRequestDeniedMsg(curr.getAccessRequest(), AccessRequestDenialReason.RESOURCE_DISABLED);
						curr.getReplyTo().tell(notification, getSelf());
					}
					
					/* Pretty sure this has to be here. If there's no users using the resource it should be disabled immediately, no? */
					if(readers.get(resourceName).isEmpty() && writers.get(resourceName).isEmpty()) { // if no user has access, disable now
						localNameToResource.get(resourceName).disable(); // disable
						ManagementRequestGrantedMsg response = new ManagementRequestGrantedMsg(msg.getRequest()); // respond
						
						LogMsg x = LogMsg.makeManagementRequestGrantedLogMsg(replyTo, getSelf(), msg.getRequest());
						System.out.println(x);
						log(x);
						
						replyTo.tell(response, replyTo);
					} else { // otherwise we add to queue to be disabled later
						usersAwaitingDisableConfirm.get(resourceName).put(replyTo, msg);
					}
				}
			}
			
			/* Handles enable */
			else if(msg.getRequest().getType().equals(ManagementRequestType.ENABLE)) {
				if(statuses.get(resourceName).equals(ResourceStatus.ENABLED)) { // already enabled
					ManagementRequestGrantedMsg response = new ManagementRequestGrantedMsg(msg.getRequest());
					
					LogMsg x = LogMsg.makeManagementRequestGrantedLogMsg(replyTo, getSelf(), msg.getRequest());
					System.out.println(x);
					log(x);
					
					replyTo.tell(response, getSelf());
				} else {
					statuses.put(resourceName, ResourceStatus.ENABLED);
					localNameToResource.get(resourceName).enable();
					ManagementRequestGrantedMsg response = new ManagementRequestGrantedMsg(msg.getRequest());
					
					LogMsg x = LogMsg.makeManagementRequestGrantedLogMsg(replyTo, getSelf(), msg.getRequest());
					System.out.println(x);
					log(x);
					
					replyTo.tell(response, getSelf());
				}
			}
		}
		
		
		/* RESOURCE IS NOT LOCAL, MUST FORWARD OR FIND RESOURCE */
		else {
			if(nonexistant.contains(resourceName)) {
				LogMsg x = LogMsg.makeManagementRequestDeniedLogMsg(foreignResourcesToManagers.get(resourceName), getSelf(), msg.getRequest(), ManagementRequestDenialReason.RESOURCE_NOT_FOUND);
				System.out.println(x);
				log(x); // log
				
				ManagementRequestDeniedMsg toSend = new ManagementRequestDeniedMsg(msg.getRequest(), ManagementRequestDenialReason.RESOURCE_NOT_FOUND);
				replyTo.tell(toSend, getSelf());
			} else if(foreignResourcesToManagers.containsKey(resourceName)) { // Checks if we know where the resource is, then we can just forward directly
				
				LogMsg x = LogMsg.makeManagementRequestForwardedLogMsg(getSelf(), foreignResourcesToManagers.get(resourceName), msg.getRequest());
				System.out.println(x);
				log(x); // log
				
				foreignResourcesToManagers.get(resourceName).tell(msg, getSelf()); // Forwarding message to foreign resource
			} else if(!foreignResourcesToManagers.containsKey(resourceName) && responseCount.containsKey(resourceName)) { // Being searched for already, not found yet
				foreignWaitingRequests.get(resourceName).add(msg);
			} else { // Send WhoHasResourceRequestMsg's
				responseCount.put(resourceName, managers.size());
				foreignWaitingRequests.put(resourceName, new LinkedList<Object>());
				foreignWaitingRequests.get(resourceName).add(msg);
				for(ActorRef manager : managers) {
					System.out.println("Sending...");
					WhoHasResourceRequestMsg req = new WhoHasResourceRequestMsg(resourceName);
					manager.tell(req, getSelf());
				}
			}
		}
	}
	
	
	/* Handles WhoHasResourceReleaseMsg */
	private void recvAccessReleaseMsg(AccessReleaseMsg msg) {
		String resourceName = msg.getAccessRelease().getResourceName(); // Stores name of resource
		
		LogMsg x = LogMsg.makeAccessReleaseReceivedLogMsg(msg.getSender(), getSelf(), msg.getAccessRelease());
		System.out.println(x);
		log(x);
		
		if(localResourcesNames.contains(resourceName)) {
			AccessType releaseType = msg.getAccessRelease().getType(); // Gets type of access to release
			ActorRef user = msg.getSender();
			
			/* Releasing local read access */
			if(releaseType.equals(AccessType.CONCURRENT_READ)) {
				if(readers.get(resourceName).contains(user)) { // if it has an access
					Integer currAccesses = readReentrantManager.get(resourceName).get(user); // Current number of access tokens
					if(currAccesses == 1) { // if only 1 access token held
						readers.get(resourceName).remove(user); // Removing user from readers list
						readReentrantManager.get(resourceName).remove(user); // Removing from reentrancy hash
						
						x = LogMsg.makeAccessReleasedLogMsg(user, getSelf(), msg.getAccessRelease());
						System.out.println(x);
						log(x);
						
						/*Now since all tokens of this type released, must check if pending disable*/
						if(statuses.get(resourceName).equals(ResourceStatus.DISABLED) && readers.get(resourceName).isEmpty() && writers.get(resourceName).isEmpty()) { // Resource entirely 
							/* Iterate through users waiting on disable, send them granted messages and disable resource */
							for (Map.Entry<ActorRef, ManagementRequestMsg> entry : usersAwaitingDisableConfirm.get(resourceName).entrySet()) {
								ActorRef key = entry.getKey();
								ManagementRequestMsg val = entry.getValue();
								ManagementRequestGrantedMsg response = new ManagementRequestGrantedMsg(val);
								
								x = LogMsg.makeManagementRequestGrantedLogMsg(user, getSelf(), val.getRequest()); // log
								System.out.println(x);
								log(x);
								
								key.tell(response, getSelf());
							}
							
							x = LogMsg.makeResourceStatusChangedLogMsg(getSelf(), resourceName, ResourceStatus.DISABLED);
							System.out.println(x);
							log(x);
							
							localNameToResource.get(resourceName).disable(); // disabling
						} else { // Now we have to check blocked requests
							if(!requestQueues.get(resourceName).isEmpty()) { // not empty, must process next request
								AccessRequestMsg next = requestQueues.get(resourceName).poll();
								AccessRequestType type = next.getAccessRequest().getType();
								if(type.equals(AccessRequestType.EXCLUSIVE_WRITE_BLOCKING)) {
									writers.get(resourceName).add(next.getReplyTo());
									writeReentrantManager.get(resourceName).put(next.getReplyTo(), 1);
									
									x = LogMsg.makeAccessRequestGrantedLogMsg(next.getReplyTo(), getSelf(), next.getAccessRequest()); 	// log
									System.out.println(x);
									log(x);
									
									AccessRequestGrantedMsg toSend = new AccessRequestGrantedMsg(next.getAccessRequest());
									next.getReplyTo().tell(toSend, getSelf());
								} else if(type.equals(AccessRequestType.CONCURRENT_READ_BLOCKING)) {
									readers.get(resourceName).add(next.getReplyTo());
									readReentrantManager.get(resourceName).put(next.getReplyTo(), 1);
									
									x = LogMsg.makeAccessRequestGrantedLogMsg(next.getReplyTo(), getSelf(), next.getAccessRequest()); 	// log
									System.out.println(x);
									log(x);
									
									AccessRequestGrantedMsg toSend = new AccessRequestGrantedMsg(next.getAccessRequest());
									next.getReplyTo().tell(toSend, getSelf());
								}
							}
						}
					} else {
						Integer oldAccesses = readReentrantManager.get(resourceName).get(user); // Getting old # of accesses
						readReentrantManager.get(resourceName).put(user, oldAccesses - 1); // Updating value
						
						x = LogMsg.makeAccessReleasedLogMsg(user, getSelf(), msg.getAccessRelease());
						System.out.println(x);
						log(x);
					}
				} else {
					x = LogMsg.makeAccessReleaseIgnoredLogMsg(msg.getSender(), getSelf(), msg.getAccessRelease());
					System.out.println(x);
					log(x);
				}
			} 
			
			/* Releasing local write access */
			else if(releaseType.equals(AccessType.EXCLUSIVE_WRITE)){
				if(writers.get(resourceName).contains(user)) {
					Integer currAccesses = writeReentrantManager.get(resourceName).get(user); // Current number of access tokens
					if(currAccesses == 1) {
						writers.get(resourceName).remove(user); // Removing user from readers list
						writeReentrantManager.get(resourceName).remove(user); // Removing from reentrancy hash
						
						x = LogMsg.makeAccessReleasedLogMsg(user, getSelf(), msg.getAccessRelease());
						System.out.println(x);
						log(x);
						
						/*Now since all tokens of this type released, must check if pending disable*/
						if(statuses.get(resourceName).equals(ResourceStatus.DISABLED) && readers.get(resourceName).isEmpty() && writers.get(resourceName).isEmpty()) { // Resource entirely 
							/* Iterate through users waiting on disable, send them granted messages and disable resource */
							for (Map.Entry<ActorRef, ManagementRequestMsg> entry : usersAwaitingDisableConfirm.get(resourceName).entrySet()) {
								ActorRef key = entry.getKey();
								ManagementRequestMsg val = entry.getValue();
								ManagementRequestGrantedMsg response = new ManagementRequestGrantedMsg(val);
								key.tell(response, getSelf());
								
								x = LogMsg.makeManagementRequestGrantedLogMsg(user, getSelf(), val.getRequest()); // log
								System.out.println(x);
								log(x);
							}
							x = LogMsg.makeResourceStatusChangedLogMsg(getSelf(), resourceName, ResourceStatus.DISABLED);
							System.out.println(x);
							log(x);
							localNameToResource.get(resourceName).disable(); // disabling
						} else {
							if(!requestQueues.get(resourceName).isEmpty()) { // not empty, must process next request
								AccessRequestMsg next = requestQueues.get(resourceName).poll();
								AccessRequestType type = next.getAccessRequest().getType();
								if(type.equals(AccessRequestType.EXCLUSIVE_WRITE_BLOCKING)) {
									writers.get(resourceName).add(next.getReplyTo());
									writeReentrantManager.get(resourceName).put(next.getReplyTo(), 1);
									
									x = LogMsg.makeAccessRequestGrantedLogMsg(next.getReplyTo(), getSelf(), next.getAccessRequest()); 	// log
									System.out.println(x);
									log(x);
									
									AccessRequestGrantedMsg toSend = new AccessRequestGrantedMsg(next.getAccessRequest());
									next.getReplyTo().tell(toSend, getSelf());
								} else if(type.equals(AccessRequestType.CONCURRENT_READ_BLOCKING)) {
									readers.get(resourceName).add(next.getReplyTo());
									readReentrantManager.get(resourceName).put(next.getReplyTo(), 1);
									
									x = LogMsg.makeAccessRequestGrantedLogMsg(next.getReplyTo(), getSelf(), next.getAccessRequest()); 	// log
									System.out.println(x);
									log(x);
									
									AccessRequestGrantedMsg toSend = new AccessRequestGrantedMsg(next.getAccessRequest());
									next.getReplyTo().tell(toSend, getSelf());
								}
							}
						}
					} else {
						Integer oldAccesses = writeReentrantManager.get(resourceName).get(user); // Getting old # of accesses
						writeReentrantManager.get(resourceName).put(user, oldAccesses - 1); // Updating value
						
						x = LogMsg.makeAccessReleasedLogMsg(user, getSelf(), msg.getAccessRelease());
						System.out.println(x);
						log(x);
					}
				}  else {
					x = LogMsg.makeAccessReleaseIgnoredLogMsg(msg.getSender(), getSelf(), msg.getAccessRelease());
					System.out.println(x);
					log(x);
				}
			}
		}
		
		/* RESOURCE IS NOT LOCAL, MUST FORWARD OR FIND RESOURCE*/
		else {
			if(foreignResourcesToManagers.containsKey(resourceName)) { // Checks if we know where the resource is, then we can just forward directly
				
				x = LogMsg.makeAccessReleaseForwardedLogMsg(getSelf(), foreignResourcesToManagers.get(resourceName), msg.getAccessRelease());
				System.out.println(x);
				log(x); // log
				
				foreignResourcesToManagers.get(resourceName).tell(msg, getSelf()); // Forwarding message to foreign resource
			} else if(!foreignResourcesToManagers.containsKey(resourceName) && responseCount.containsKey(resourceName)) { // Being searched for already, not found yet
				foreignWaitingRequests.get(resourceName).add(msg);
			} else { // Send WhoHasResourceRequestMsg's
				responseCount.put(resourceName, managers.size());
				foreignWaitingRequests.put(resourceName, new LinkedList<Object>());
				foreignWaitingRequests.get(resourceName).add(msg);
				for(ActorRef manager : managers) {
					WhoHasResourceRequestMsg req = new WhoHasResourceRequestMsg(resourceName);
					manager.tell(req, getSelf());
				}
			}
		}
	}
	
	
	/* Handles WhoHasResourceRequestMsg */
	private void recvWhoHasResourceRequestMsg(WhoHasResourceRequestMsg msg, ActorRef replyTo) {
		String name = msg.getResourceName();
		if(localResourcesNames.contains(name)) { // Checking if resource is in this manager
			// Replying with true if so
			WhoHasResourceResponseMsg response = new WhoHasResourceResponseMsg(name, true, getSelf()); 
			replyTo.tell(response, getSelf());
		} else {
			// Replying with false if not
			WhoHasResourceResponseMsg response = new WhoHasResourceResponseMsg(name, false, getSelf());
			replyTo.tell(response, getSelf());
		}
	}
	
	
	/* Handles WhoHasResourceResponseMsg */
	private void recvWhoHasResourceResponseMsg(WhoHasResourceResponseMsg msg, ActorRef replyTo) {
		String name = msg.getResourceName();
		
		if(msg.getResult()) { // resource found!
			foreignResourcesToManagers.put(name, msg.getSender()); // storing location of sender
			for(Object toForward : foreignWaitingRequests.get(name)) {
				if(toForward instanceof AccessRequestMsg) {
					AccessRequestMsg forward = (AccessRequestMsg) toForward;
					
					LogMsg x = LogMsg.makeAccessRequestForwardedLogMsg(getSelf(), replyTo, forward.getAccessRequest());
					System.out.println(x);
					log(x); // log
					
					replyTo.tell((AccessRequestMsg) toForward, getSelf());
				} else if(toForward instanceof AccessReleaseMsg) {
					AccessReleaseMsg forward = (AccessReleaseMsg) toForward;
					
					LogMsg x = LogMsg.makeAccessReleaseForwardedLogMsg(getSelf(), replyTo, forward.getAccessRelease());
					System.out.println(x);
					log(x); // log
					
					replyTo.tell((AccessReleaseMsg) toForward, getSelf());
				} else if(toForward instanceof ManagementRequestMsg) {
					ManagementRequestMsg forward = (ManagementRequestMsg) toForward;
					
					LogMsg x = LogMsg.makeManagementRequestForwardedLogMsg(getSelf(), replyTo, forward.getRequest());
					System.out.println(x);
					log(x); // log
					
					replyTo.tell((ManagementRequestMsg) toForward, getSelf());
				}
			}
		} else { // resource not found, decrement count
			Integer count = responseCount.get(name);
			responseCount.put(name, count - 1);
			if(responseCount.get(name) == 0) { // resource wasn't found
				for(Object toForward : foreignWaitingRequests.get(name)) {
					if(toForward instanceof AccessRequestMsg) {
						AccessRequestMsg x = (AccessRequestMsg) toForward;
						AccessRequestDeniedMsg toSend = new AccessRequestDeniedMsg(x.getAccessRequest(), AccessRequestDenialReason.RESOURCE_NOT_FOUND);
						
						LogMsg ms = LogMsg.makeAccessRequestDeniedLogMsg(replyTo, getSelf(), x.getAccessRequest(), AccessRequestDenialReason.RESOURCE_NOT_FOUND);
						System.out.println(ms);
						log(ms);
						
						x.getReplyTo().tell(toSend, getSelf());
					} else if(toForward instanceof ManagementRequestMsg) {
						ManagementRequestMsg x = (ManagementRequestMsg) toForward;
						ManagementRequestDeniedMsg toSend = new ManagementRequestDeniedMsg(x.getRequest(), ManagementRequestDenialReason.RESOURCE_NOT_FOUND);
						
						LogMsg ms = LogMsg.makeManagementRequestDeniedLogMsg(replyTo, getSelf(), x.getRequest(), ManagementRequestDenialReason.RESOURCE_NOT_FOUND);
						System.out.println(msg);
						log(ms);
						
						x.getReplyTo().tell(toSend, getSelf());
					}
				}
				nonexistant.add(name);
				//responseCount.remove(name); // remove from responseCount
			}
		}
	}
}