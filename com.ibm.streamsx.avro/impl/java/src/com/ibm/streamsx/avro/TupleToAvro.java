//
// *******************************************************************************
// * Copyright (C)2018, International Business Machines Corporation and *
// * others. All Rights Reserved. *
// *******************************************************************************
//

package com.ibm.streamsx.avro;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.log4j.Logger;

import com.ibm.streams.operator.AbstractOperator;
import com.ibm.streams.operator.Attribute;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.StreamingInput;
import com.ibm.streams.operator.StreamingOutput;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.OperatorContext.ContextCheck;
import com.ibm.streams.operator.StreamingData.Punctuation;
import com.ibm.streams.operator.Type.MetaType;
import com.ibm.streams.operator.compile.OperatorContextChecker;
import com.ibm.streams.operator.log4j.TraceLevel;
import com.ibm.streams.operator.model.InputPortSet;
import com.ibm.streams.operator.model.InputPortSet.WindowMode;
import com.ibm.streams.operator.model.InputPortSet.WindowPunctuationInputMode;
import com.ibm.streams.operator.model.Icons;
import com.ibm.streams.operator.model.InputPorts;
import com.ibm.streams.operator.model.OutputPortSet;
import com.ibm.streams.operator.model.Libraries;
import com.ibm.streams.operator.model.OutputPortSet.WindowPunctuationOutputMode;
import com.ibm.streams.operator.state.ConsistentRegionContext;
import com.ibm.streams.operator.model.OutputPorts;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.model.PrimitiveOperator;
import com.ibm.streams.operator.types.ValueFactory;
import com.ibm.streamsx.avro.convert.TupleToAvroConverter;

/**
 * Processes tuples and converts them to Avro
 * 
 */

@PrimitiveOperator(name = TupleToAvro.OPER_NAME, namespace = "com.ibm.streamsx.avro", description = TupleToAvro.DESC)
@InputPorts({
		@InputPortSet(description = "Port that ingests tuples.", cardinality = 1, optional = false, windowingMode = WindowMode.NonWindowed, windowPunctuationInputMode = WindowPunctuationInputMode.Oblivious) })
@OutputPorts({
		@OutputPortSet(description = "Port that produces Avro records.", cardinality = 1, optional = false, windowPunctuationOutputMode = WindowPunctuationOutputMode.Generating) })
@Icons(location16 = "icons/TupleToAvro_16.gif", location32 = "icons/TupleToAvro_32.gif")
@Libraries(value = { "opt/downloaded/*" })
public class TupleToAvro extends AbstractOperator {

	public static final String OPER_NAME = "TupleToAvro";
	
	private static Logger tracer = Logger.getLogger(TupleToAvro.class.getName());

	private String outputAvroMessage = null;
	private final String DEFAULT_OUTPUT_AVRO_MSG_ATTRIBUTE = "avroMessage";

	private String avroMessageSchemaFile = null;
	private boolean embedAvroSchema = false;
	private boolean submitOnPunct = false;
	private long bytesPerMessage = 0;
	private long tuplesPerMessage = 0;
	private long timePerMessage = 0;
	private Schema messageSchema;

	@Parameter(optional = true, description = "The ouput stream attribute which contains the output Avro message(s). This attribute must be of type blob. Default is the sole output attribute when the schema has one attribute otherwise `avroMessage`.")
	public void setOutputAvroMessage(String outputAvroMessage) {
		this.outputAvroMessage = outputAvroMessage;
	}

	@Parameter(optional = false, description = "File that contains the Avro schema to serialize the Avro message(s).")
	public void setAvroMessageSchemaFile(String avroMessageSchemaFile) {
		this.avroMessageSchemaFile = avroMessageSchemaFile;
	}

	@Parameter(optional = true, description = "Embed the schema in the generated Avro message. "
			+ "When generating Avro messages that must be persisted to a file system, "
			+ "the schema is expected to be included in the file. If this parameter is set to true, "
			+ "incoming tuples are batched and a large binary object that contains the Avro schema "
			+ "and 1 or more messages is generated. Also, you must specify one of the parameters (submitOnPunct, "
			+ "bytesPerMessage, tuplesPerMessage, timePerMessage) that controls "
			+ "when Avro message block is submitted to the output port."
			+ "After submitting the Avro message to the output port, a "
			+ "punctuation is generated so that the receiving operator can potentially create a new file.")
	public void setEmbedAvroSchema(Boolean embedAvroSchema) {
		this.embedAvroSchema = embedAvroSchema;
	}

	@Parameter(optional = true, description = "When set to true, the operator will submit the block of Avro messages what was built and generate a punctuation so that the "
			+ "receiving operator can potentially create a new file. Default is false. Only valid if Avro schema is embedded in the output.")
	public void setSubmitOnPunct(Boolean submitOnPunct) {
		this.submitOnPunct = submitOnPunct;
	}

	@Parameter(optional = true, description = "This parameter controls the minimum size in bytes that the Avro message block "
			+ "should be before it is submitted to the output port. Default is 0l. Only valid if Avro schema is embedded in the output.")
	public void setBytesPerMessage(Long bytesPerMessage) {
		this.bytesPerMessage = bytesPerMessage;
	}

	@Parameter(optional = true, description = "This parameter controls the minimum number of tuples that the Avro message "
			+ "block should contain before it is submitted to the output port. Default is 0l. Only valid if Avro schema is embedded in the output.")
	public void setTuplesPerMessage(Long tuplesPerMessage) {
		this.tuplesPerMessage = tuplesPerMessage;
	}

	@Parameter(optional = true, description = "This parameter controls the maximum time in seconds before the Avro message block "
			+ "is submitted to the output port. Default is 0l. Only valid if Avro schema is embedded in the output.")
	public void setTimePerMessage(Long timePerMessage) {
		this.timePerMessage = timePerMessage;
	}

	// Variables
	StreamingOutput<OutputTuple> outStream;
	OutputTuple outTuple;
	GenericDatumWriter<GenericRecord> avroWriter;
	DataFileWriter<GenericRecord> avroDataFileWriter;
	ByteArrayOutputStream avroMessageByteArray = new ByteArrayOutputStream();
	ByteArrayOutputStream avroBlockByteArray = new ByteArrayOutputStream();
	long lastSubmitted = System.currentTimeMillis();
	int numberOfBatchedMessages = 0;

	/**
	 * Compile time operator checks: Do not use the operator in a consistent region
	 * @param checker
	 *            The operator context
	 */
	@ContextCheck(compile = true)
	public static void checkInConsistentRegion(OperatorContextChecker checker) {
		ConsistentRegionContext consistentRegionContext = checker.getOperatorContext().getOptionalContext(ConsistentRegionContext.class);
		if(consistentRegionContext != null) {
			checker.setInvalidContext(Messages.getString("AVRO_NOT_CONSISTENT_REGION", OPER_NAME), new Object[]{});
		}
	}

	/**
	 * Initialize this operator. Called once before any tuples are processed.
	 * 
	 * @param operatorContext
	 *            OperatorContext for this operator.
	 * @throws Exception
	 *             Operator failure, will cause the enclosing PE to terminate.
	 */
	@Override
	public synchronized void initialize(OperatorContext operatorContext) throws Exception {
		// Must call super.initialize(context) to correctly setup an operator.
		super.initialize(operatorContext);
		tracer.log(TraceLevel.TRACE, "Operator " + operatorContext.getName() + " initializing in PE: "
				+ operatorContext.getPE().getPEId() + " in Job: " + operatorContext.getPE().getJobId());

		StreamSchema ssOp0 = getOutput(0).getStreamSchema();
		StreamSchema ssIp0 = getInput(0).getStreamSchema();

		// If no output Avro message attribute specified, use default
		if (outputAvroMessage == null) {
			if (ssOp0.getAttributeCount() == 1) {
				outputAvroMessage = ssOp0.getAttribute(0).getName();
			} else {
				outputAvroMessage = DEFAULT_OUTPUT_AVRO_MSG_ATTRIBUTE;
			}
		}
		Attribute outputAvroMessageAttribute = ssOp0.getAttribute(outputAvroMessage);
		if (outputAvroMessageAttribute == null) {
			tracer.log(TraceLevel.ERROR, Messages.getString("AVRO_OUTPUT_ATTRIBUTE_NOT_FOUND", "outputAvroMessage", outputAvroMessage));
			throw new IllegalArgumentException(Messages.getString("AVRO_OUTPUT_ATTRIBUTE_NOT_FOUND", "outputAvroMessage", outputAvroMessage));
		} else {
			MetaType attributeType = outputAvroMessageAttribute.getType().getMetaType();
			if(attributeType!=MetaType.BLOB) {
				tracer.log(TraceLevel.ERROR, Messages.getString("AVRO_ATTRIBUTE_WRONG_TYPE", "outputAvroMessage", outputAvroMessage, "blob"));
				throw new IllegalArgumentException(Messages.getString("AVRO_ATTRIBUTE_WRONG_TYPE", "outputAvroMessage", outputAvroMessage, "blob"));
			}
		}
		tracer.log(TraceLevel.TRACE, "Output Avro message attribute: " + outputAvroMessage);

		// Get the Avro schema file to parse the Avro messages
		tracer.log(TraceLevel.TRACE, "Retrieving and parsing Avro message schema file " + avroMessageSchemaFile);
		InputStream avscInput = new FileInputStream(avroMessageSchemaFile);
		Schema.Parser parser = new Schema.Parser();
		messageSchema = parser.parse(avscInput);

		// Check Streams and Avro schema
		boolean validMapping = TupleToAvroConverter.isValidTupleToAvroMapping(operatorContext.getName(), ssIp0,
				messageSchema);
		if (!validMapping) {
			throw new Exception(Messages.getString("AVRO_NO_SCHEMA_MATCH"));
		}

		tracer.log(TraceLevel.TRACE, "Embed Avro schema in generated output Avro message block: " + embedAvroSchema);
		tracer.log(TraceLevel.TRACE, "Submit Avro message block when punctuation is received: " + submitOnPunct);

		// submitOnPunct is only valid if Avro schema is embedded in the output
		if (!embedAvroSchema && ( submitOnPunct || (tuplesPerMessage != 0) || (bytesPerMessage != 0) || (timePerMessage != 0) ) )
			throw new Exception(Messages.getString("AVRO_EMBEDDED_SCHEMA_REQUIRED","submitOnPunct, bytesPerMessage, timePerMessage, tuplesPerMessage"));
		// If Avro schema is embedded in the output, submitOnPunct is mandatory
		if (embedAvroSchema && !submitOnPunct && tuplesPerMessage == 0 && bytesPerMessage == 0 && timePerMessage == 0)
			throw new Exception(Messages.getString("AVRO_MISSING_THRESHOLD","submitOnPunct, bytesPerMessage, timePerMessage, tuplesPerMessage"));

		// Prepare and initialize variables that don't change for every input
		// record
		avroWriter = new GenericDatumWriter<GenericRecord>(messageSchema);
		avroDataFileWriter = new DataFileWriter<GenericRecord>(avroWriter);
		if (embedAvroSchema)
			avroDataFileWriter.create(messageSchema, avroBlockByteArray);
		numberOfBatchedMessages = 0;

		tracer.log(TraceLevel.TRACE, "TupleToAvro operator initialized, ready to receive tuples");

	}

	/**
	 * Process an incoming tuple that arrived on the specified port.
	 */
	@Override
	public final void process(StreamingInput<Tuple> inputStream, Tuple tuple) throws Exception {

		if (tracer.isTraceEnabled())
			tracer.log(TraceLevel.TRACE, "Input tuple: " + tuple);

		// Create a new tuple for output port 0 and copy over any matching
		// attributes
		outStream = getOutput(0);
		outTuple = outStream.newTuple();
		outTuple.assign(tuple);

		// Parse the tuple input and assign to Avro datum
		GenericRecord datum = TupleToAvroConverter.convertTupleToAvro(tuple, inputStream.getStreamSchema(),
				messageSchema);

		try {
			// Encode the datum to Avro
			if (embedAvroSchema) {
				avroDataFileWriter.append(datum);
				avroDataFileWriter.flush();
				numberOfBatchedMessages++;
				// Check if any of the threshold parameters has been exceeded
				if (tuplesPerMessage != 0 && numberOfBatchedMessages >= tuplesPerMessage)
					submitAvroToOuput();
				if (bytesPerMessage != 0 && avroBlockByteArray.size() >= bytesPerMessage)
					submitAvroToOuput();
				if (timePerMessage != 0) {
					if (System.currentTimeMillis() >= (lastSubmitted + (1000 * timePerMessage)))
						submitAvroToOuput();
				}
			} else {
				Encoder encoder = EncoderFactory.get().binaryEncoder(avroMessageByteArray, null);
				avroWriter.write(datum, encoder);
				encoder.flush();
				submitAvroToOuput();
			}
		} catch (Exception e) {
			tracer.log(TraceLevel.ERROR, "Error while converting tuple to AVRO schema: " + e.getMessage() + ". Tuple: " + inputStream);
			e.printStackTrace();
		}
	}

	// Submit the Avro byte array to the output port and reset byte array
	private void submitAvroToOuput() throws Exception {
		// Send block of messages with Avro schema included and punctuation
		if (embedAvroSchema) {
			if (numberOfBatchedMessages > 0) {
				if (tracer.isTraceEnabled())
					tracer.log(TraceLevel.TRACE, "Submitting " + numberOfBatchedMessages
							+ " Avro messages with a total length of " + avroBlockByteArray.size() + " bytes");
				outTuple.setBlob(outputAvroMessage, ValueFactory.newBlob(avroBlockByteArray.toByteArray()));
				outStream.submit(outTuple);
				outStream.punctuate(Punctuation.WINDOW_MARKER);
				// Reset for the next block
				avroBlockByteArray.reset();
				avroDataFileWriter.close();
				avroDataFileWriter.create(messageSchema, avroBlockByteArray);
				lastSubmitted = System.currentTimeMillis();
				numberOfBatchedMessages = 0;
			}
		} else { // Send individual message
			if (tracer.isTraceEnabled())
				tracer.log(TraceLevel.TRACE,
						"Submitting Avro message with length " + avroMessageByteArray.size() + " bytes");
			outTuple.setBlob(outputAvroMessage, ValueFactory.newBlob(avroMessageByteArray.toByteArray()));
			outStream.submit(outTuple);
			// Reset for the next message
			avroMessageByteArray.reset();
		}
	}

	/**
	 * Process the punctuation. If Avro messages are batched, the Avro message
	 * is submitted if a window punctuation is received and submitOnPunct is
	 * true, or when the final punctuation is received.
	 */
	public void processPunctuation(StreamingInput<Tuple> inputStream, Punctuation mark) throws Exception {
		// If Avro messages are batched, submit current batch and punctuation if
		// submitOnPunct
		if (embedAvroSchema) {
			if (submitOnPunct && mark == Punctuation.WINDOW_MARKER)
				submitAvroToOuput();
			if (mark == Punctuation.FINAL_MARKER)
				submitAvroToOuput();
		}
		// Else forward window punctuation mark to the output port
		else
			super.processPunctuation(inputStream, mark);
	}

	static final String DESC = "This operator converts Streams tuples into binary Avro messages. The input tuples can be"
			+ "nested types with lists and tuples, but the attribute types must be mappable to the Avro primitive types. "
			+ "boolean, float32, float64, int32, int64, rstring and ustring are respectively mapped to "
			+ "Boolean, Float, Double, Integer, Long, String.\\n\\n"
			+ "If parameter `embedAvroSchema` is false, the operator passes window punctuation marker transparently to the output port. "
			+ "If parameter `embedAvroSchema` is true, the operator generates window punctuation markers.\\n\\n"
			+ "If the output message attribute is not found or has no blob type, the operator will fail.\\n\\n"
			+ "This operator must not be used inside a consistent region.";

}
