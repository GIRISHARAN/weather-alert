import json
import boto3

def lambda_handler(event, context):
    # Configure the AWS clients
    sqs_client = boto3.client('sqs')
    sns_client = boto3.client('sns')
    
    record = event['Records'][0]  # Assuming only one record
    
    # Extract the email and temperature data from the SQS message
    msg_body_str = record['body']
    msg_body = json.loads(msg_body_str)
    print("Msg Body:", msg_body)
    email = msg_body.get('gmail')
    temperature = msg_body.get('Temperature')
    
    print("Email:", email)
    print("Temp:", temperature)
    
    if email and temperature:
        # Create an SNS message with the temperature information
        sns_message = f"Temperature Information: {temperature}"
        
        # Publish the message to the SNS topic
        topic_arn = 'arn:aws:sns:us-east-1:923540452677:Weather-SNS-Topic-Test'
        
        response = sns_client.publish(
            TargetArn=topic_arn,
            Message=sns_message,  # Sending only the temperature information
            MessageAttributes={
                'email': {
                    'DataType': 'String',
                    'StringValue': email
                }
            }
        )
        
        print(f"Message sent to {email}: {sns_message}")
    
    # Delete the SQS message after processing
    receipt_handle = record['receiptHandle']
    queue_url = 'https://sqs.us-east-1.amazonaws.com/923540452677/Weather-SQS-Test'
    sqs_client.delete_message(QueueUrl=queue_url, ReceiptHandle=receipt_handle)
